package io.forgetdm.core.mask;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.core.temenos.TemenosCodec;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Applies semantic masking rules to leaves embedded in Temenos dynamic arrays or XML columns. */
public final class StructuredMaskingCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, Config> CACHE = new ConcurrentHashMap<>();
    private static final Pattern INDEX = Pattern.compile("\\[\\d+]");
    private static final char[] MARKS = { TemenosCodec.FM, TemenosCodec.VM, TemenosCodec.SVM, TemenosCodec.TM };
    private static final String[] LABELS = { "FM", "VM", "SVM", "TM" };

    public record RuleSpec(String selector, String function, String salt, String param1, String param2) {}
    public record Config(String format, List<RuleSpec> rules) {}

    private StructuredMaskingCodec() {}

    public static String encode(String format, List<RuleSpec> rules) {
        try {
            return JSON.writeValueAsString(new Config(format == null ? "TEMENOS" : format.toUpperCase(Locale.ROOT),
                    rules == null ? List.of() : List.copyOf(rules)));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Structured masking plan could not be encoded", e);
        }
    }

    public static Config decode(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Structured masking plan is empty");
        if (CACHE.size() > 1_024) CACHE.clear();
        return CACHE.computeIfAbsent(json, key -> {
            try {
                Config parsed = JSON.readValue(key, Config.class);
                if (parsed.rules() == null || parsed.rules().isEmpty())
                    throw new IllegalArgumentException("Structured masking plan has no leaf rules");
                return parsed;
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException("Structured masking plan is invalid", e);
            }
        });
    }

    public static String mask(MaskingEngine engine, String configJson, String value, MaskContext context) {
        if (value == null || value.isEmpty()) return value;
        Config config = decode(configJson);
        String format = config.format() == null ? "" : config.format().toUpperCase(Locale.ROOT);
        if ("XML".equals(format)) {
            if (!value.stripLeading().startsWith("<")) return maskUnstructuredScalar(engine, config, value, context);
            return maskXml(engine, config, value, context);
        }
        return maskTemenos(engine, config, value, context);
    }

    private static String maskTemenos(MaskingEngine engine, Config config, String value, MaskContext context) {
        return mapTemenosLevel(engine, config, value, context, 0, "$", null);
    }

    private static String mapTemenosLevel(MaskingEngine engine, Config config, String value, MaskContext context,
                                          int level, String path, String inheritedKey) {
        int next = -1;
        for (int i = level; i < MARKS.length; i++) {
            if (value.indexOf(MARKS[i]) >= 0) { next = i; break; }
        }
        if (next < 0) return maskTemenosLeaf(engine, config, path, value, context, inheritedKey);
        String payload = value;
        String prefix = "";
        String key = inheritedKey;
        int equals = value.indexOf('=');
        int delimiter = value.indexOf(MARKS[next]);
        if (next > 0 && equals > 0 && equals < delimiter && equals <= 80) {
            String candidate = value.substring(0, equals).trim();
            if (candidate.matches("[A-Za-z0-9_.-]+")) {
                key = candidate;
                prefix = value.substring(0, equals + 1);
                payload = value.substring(equals + 1);
            }
        }
        List<String> parts = split(payload, MARKS[next]);
        StringBuilder out = new StringBuilder(value.length()).append(prefix);
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) out.append(MARKS[next]);
            out.append(mapTemenosLevel(engine, config, parts.get(i), context, next + 1,
                    path + "/" + LABELS[next] + "[" + (i + 1) + "]", key));
        }
        return out.toString();
    }

    private static String maskTemenosLeaf(MaskingEngine engine, Config config, String path, String raw,
                                          MaskContext context, String inheritedKey) {
        if (raw == null || raw.isEmpty()) return raw;
        String prefix = "";
        String value = raw;
        String leafPath = path;
        int equals = raw.indexOf('=');
        if (equals > 0 && equals <= 80) {
            String key = raw.substring(0, equals).trim();
            if (key.matches("[A-Za-z0-9_.-]+")) {
                prefix = raw.substring(0, equals + 1);
                value = raw.substring(equals + 1);
                leafPath += "/" + key;
            }
        } else if (inheritedKey != null) {
            leafPath += "/" + inheritedKey;
        }
        RuleSpec rule = match(config.rules(), leafPath);
        if (rule == null) return raw;
        String masked = apply(engine, rule, value, context);
        return prefix + (masked == null ? "" : masked);
    }

    private static String maskXml(MaskingEngine engine, Config config, String value, MaskContext context) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY"))
            throw new IllegalArgumentException("XML with DTD or entity declarations cannot be masked");
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(value)));
            Element root = document.getDocumentElement();
            maskXmlElement(engine, config, root, "$/" + localName(root) + "[1]", context);

            TransformerFactory transformers = TransformerFactory.newInstance();
            transformers.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            transformers.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = transformers.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION,
                    value.stripLeading().startsWith("<?xml") ? "no" : "yes");
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            StringWriter out = new StringWriter(value.length());
            transformer.transform(new DOMSource(document), new StreamResult(out));
            return out.toString();
        } catch (Exception e) {
            throw new IllegalArgumentException("Structured XML masking failed: " + e.getMessage(), e);
        }
    }

    private static void maskXmlElement(MaskingEngine engine, Config config, Element element, String path,
                                       MaskContext context) {
        NamedNodeMap attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (attribute.getNodeName().startsWith("xmlns")) continue;
            RuleSpec rule = match(config.rules(), path + "/@" + localName(attribute));
            if (rule != null) {
                String masked = apply(engine, rule, attribute.getNodeValue(), context);
                attribute.setNodeValue(masked == null ? "" : masked);
            }
        }

        Map<String, Integer> occurrences = new LinkedHashMap<>();
        boolean hasElementChild = false;
        NodeList children = element.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element childElement) {
                hasElementChild = true;
                String name = localName(childElement);
                int occurrence = occurrences.merge(name, 1, Integer::sum);
                maskXmlElement(engine, config, childElement, path + "/" + name + "[" + occurrence + "]", context);
            }
        }
        if (hasElementChild) return;
        RuleSpec rule = match(config.rules(), path);
        if (rule == null) return;
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                String original = child.getNodeValue();
                if (original == null || original.isBlank()) continue;
                String leading = original.substring(0, original.length() - original.stripLeading().length());
                String trailing = original.substring(original.stripTrailing().length());
                String masked = apply(engine, rule, original.trim(), context);
                child.setNodeValue(leading + (masked == null ? "" : masked) + trailing);
            }
        }
    }

    private static String maskUnstructuredScalar(MaskingEngine engine, Config config, String value, MaskContext context) {
        if (config.rules().size() != 1) return value;
        return apply(engine, config.rules().get(0), value, context);
    }

    private static String apply(MaskingEngine engine, RuleSpec rule, String value, MaskContext context) {
        MaskFunction function;
        try { function = MaskFunction.valueOf(rule.function().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw new IllegalArgumentException("Unknown structured masking function " + rule.function()); }
        String salt = rule.salt() == null || rule.salt().isBlank()
                ? "structured." + function.name().toLowerCase(Locale.ROOT) : rule.salt();
        return engine.mask(function, salt, value, rule.param1(), rule.param2(), context);
    }

    private static RuleSpec match(List<RuleSpec> rules, String actualPath) {
        String normalized = normalize(actualPath);
        for (RuleSpec rule : rules) if (normalize(rule.selector()).equals(normalized)) return rule;
        String tail = tail(normalized);
        RuleSpec found = null;
        for (RuleSpec rule : rules) {
            if (!tail(rule.selector()).equalsIgnoreCase(tail)) continue;
            if (found != null) return null;
            found = rule;
        }
        return found;
    }

    public static String normalize(String selector) {
        if (selector == null) return "";
        return INDEX.matcher(selector.trim()).replaceAll("[*]");
    }

    private static String tail(String selector) {
        String normalized = normalize(selector);
        return normalized.substring(normalized.lastIndexOf('/') + 1).replace("[*]", "");
    }

    private static String localName(Node node) {
        return node.getLocalName() == null ? node.getNodeName().replaceFirst("^.*:", "") : node.getLocalName();
    }

    private static List<String> split(String value, char mark) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == mark) { out.add(value.substring(start, i)); start = i + 1; }
        }
        out.add(value.substring(start));
        return out;
    }
}
