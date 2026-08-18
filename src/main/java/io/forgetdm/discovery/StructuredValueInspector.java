package io.forgetdm.discovery;

import io.forgetdm.core.temenos.TemenosCodec;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Bounded, non-expanding inspection of structured values held in one database column. */
final class StructuredValueInspector {
    static final int MAX_LEAVES = 256;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_LEAF_CHARS = 2_048;

    enum Format { SCALAR, TEMENOS, XML }

    record Leaf(String path, String value, String semanticName) {}
    record Inspection(Format format, List<Leaf> leaves, boolean truncated) {}

    private StructuredValueInspector() {}

    static Inspection inspect(String value) {
        if (value == null || value.isBlank()) return new Inspection(Format.SCALAR, List.of(), false);
        if (TemenosCodec.hasMarkers(value)) return inspectTemenos(value);
        String trimmed = value.stripLeading();
        if (trimmed.startsWith("<")) {
            Inspection xml = inspectXml(value);
            if (xml != null) return xml;
        }
        return new Inspection(Format.SCALAR,
                List.of(new Leaf("$", bounded(value), null)), value.length() > MAX_LEAF_CHARS);
    }

    private static Inspection inspectTemenos(String value) {
        List<Leaf> leaves = new ArrayList<>();
        boolean[] truncated = { false };
        collectTemenos(value, 0, "$", null, null, leaves, truncated);
        return new Inspection(Format.TEMENOS, List.copyOf(leaves), truncated[0]);
    }

    private static void collectTemenos(String value, int level, String path,
                                       String inheritedKey, String inheritedSemanticName,
                                       List<Leaf> leaves, boolean[] truncated) {
        if (leaves.size() >= MAX_LEAVES) {
            truncated[0] = true;
            return;
        }
        char[] marks = { TemenosCodec.FM, TemenosCodec.VM, TemenosCodec.SVM, TemenosCodec.TM };
        String[] labels = { "FM", "VM", "SVM", "TM" };
        int next = -1;
        for (int i = level; i < marks.length; i++) {
            if (value.indexOf(marks[i]) >= 0) {
                next = i;
                break;
            }
        }
        if (next < 0) {
            addTemenosLeaf(path, value, inheritedKey, inheritedSemanticName, leaves, truncated);
            return;
        }
        String payload = value;
        String key = inheritedKey;
        String semanticName = inheritedSemanticName;
        int equals = value.indexOf('=');
        int delimiter = value.indexOf(marks[next]);
        if (next > 0 && equals > 0 && equals < delimiter && equals <= 80) {
            String candidate = value.substring(0, equals).trim();
            if (candidate.matches("[A-Za-z0-9_.-]+")) {
                key = candidate;
                semanticName = semanticAlias(candidate);
                payload = value.substring(equals + 1);
            }
        }
        List<String> parts = split(payload, marks[next]);
        for (int i = 0; i < parts.size(); i++) {
            collectTemenos(parts.get(i), next + 1,
                    path + "/" + labels[next] + "[" + (i + 1) + "]",
                    key, semanticName, leaves, truncated);
            if (leaves.size() >= MAX_LEAVES) {
                if (i + 1 < parts.size()) truncated[0] = true;
                return;
            }
        }
    }

    private static void addTemenosLeaf(String path, String raw, String inheritedKey,
                                       String inheritedSemanticName, List<Leaf> leaves, boolean[] truncated) {
        if (raw == null || raw.isBlank()) return;
        String semanticName = inheritedSemanticName;
        String value = raw;
        int equals = raw.indexOf('=');
        if (equals > 0 && equals <= 80) {
            String candidate = raw.substring(0, equals).trim();
            if (candidate.matches("[A-Za-z0-9_.-]+")) {
                semanticName = semanticAlias(candidate);
                path += "/" + candidate;
                value = raw.substring(equals + 1);
            }
        } else if (inheritedKey != null) {
            path += "/" + inheritedKey;
        }
        if (value.length() > MAX_LEAF_CHARS) truncated[0] = true;
        leaves.add(new Leaf(path, bounded(value), semanticName));
    }

    private static Inspection inspectXml(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        if (upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) return null;
        List<Leaf> leaves = new ArrayList<>();
        boolean truncated = false;
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            set(factory, XMLInputFactory.SUPPORT_DTD, false);
            set(factory, "javax.xml.stream.isSupportingExternalEntities", false);
            set(factory, XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(new StringReader(value));
            Deque<XmlFrame> stack = new ArrayDeque<>();
            Deque<Map<String, Integer>> childCounts = new ArrayDeque<>();
            childCounts.push(new LinkedHashMap<>());
            try {
                while (reader.hasNext() && leaves.size() < MAX_LEAVES) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        if (stack.size() >= MAX_DEPTH) return new Inspection(Format.XML, List.copyOf(leaves), true);
                        String name = reader.getLocalName();
                        Map<String, Integer> siblings = childCounts.peek();
                        int occurrence = siblings.merge(name, 1, Integer::sum);
                        String parentPath = stack.isEmpty() ? "$" : stack.peek().path;
                        String path = parentPath + "/" + name + "[" + occurrence + "]";
                        if (!stack.isEmpty()) stack.peek().hasChild = true;
                        XmlFrame frame = new XmlFrame(path, name);
                        stack.push(frame);
                        childCounts.push(new LinkedHashMap<>());
                        for (int i = 0; i < reader.getAttributeCount() && leaves.size() < MAX_LEAVES; i++) {
                            String attrName = reader.getAttributeLocalName(i);
                            String attrValue = reader.getAttributeValue(i);
                            if (attrValue != null && !attrValue.isBlank()) {
                                leaves.add(new Leaf(path + "/@" + attrName, bounded(attrValue), semanticAlias(attrName)));
                                if (attrValue.length() > MAX_LEAF_CHARS) truncated = true;
                            }
                        }
                    } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                            && !stack.isEmpty()) {
                        stack.peek().text.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT && !stack.isEmpty()) {
                        XmlFrame frame = stack.pop();
                        childCounts.pop();
                        String text = frame.text.toString().trim();
                        if (!frame.hasChild && !text.isBlank()) {
                            leaves.add(new Leaf(frame.path, bounded(text), semanticAlias(frame.name)));
                            if (text.length() > MAX_LEAF_CHARS) truncated = true;
                        }
                    }
                }
                if (reader.hasNext()) truncated = true;
            } finally {
                reader.close();
            }
            return new Inspection(Format.XML, List.copyOf(leaves), truncated);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void set(XMLInputFactory factory, String key, Object value) {
        try { factory.setProperty(key, value); } catch (IllegalArgumentException ignored) { }
    }

    static String semanticAlias(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String normalized = raw.replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "name", "nm", "party_name", "customer_name" -> "full_name";
            case "id_no", "id_number", "national_identifier" -> "national_id";
            case "email_adr", "email_address" -> "email";
            case "phne_nb", "phone_number", "mobile_number" -> "phone";
            case "pstl_adr", "postal_address" -> "full_address";
            case "bicfi" -> "swift_bic";
            default -> normalized;
        };
    }

    private static List<String> split(String value, char mark) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) == mark) {
                out.add(value.substring(start, i));
                start = i + 1;
            }
        }
        out.add(value.substring(start));
        return out;
    }

    private static String bounded(String value) {
        return value.length() <= MAX_LEAF_CHARS ? value : value.substring(0, MAX_LEAF_CHARS);
    }

    private static final class XmlFrame {
        private final String path;
        private final String name;
        private final StringBuilder text = new StringBuilder();
        private boolean hasChild;

        private XmlFrame(String path, String name) {
            this.path = path;
            this.name = name;
        }
    }
}
