package io.forgetdm.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class ScenarioCompiler {
    private static final int MAX_CASES = 64;
    private final ObjectMapper json;

    public ScenarioCompiler(ObjectMapper json) {
        this.json = json;
    }

    public Compilation compile(Input input) {
        Set<String> techniques = techniques(input.coverage());
        Map<String, Object> baseline = baseline(input.preconditions(), input.parameters());
        List<CaseSpec> cases = new ArrayList<>();
        cases.add(caseSpec("baseline", "Baseline valid state", "BASELINE", baseline, input.expected()));

        if (techniques.contains("BOUNDARY")) {
            addBoundaries(cases, baseline, input.coverage(), input.preconditions(), input.expected());
        }
        if (techniques.contains("NEGATIVE")) {
            addNegative(cases, baseline, input.preconditions(), input.expected());
        }
        if (techniques.contains("STATE_TRANSITION")) {
            Map<String, Object> values = new LinkedHashMap<>(baseline);
            values.put("_event", nodeValue(input.event()));
            cases.add(caseSpec("state-transition", "Required event and state transition",
                    "STATE_TRANSITION", values, input.expected()));
        }
        if (techniques.contains("PAIRWISE")) {
            addPairwise(cases, baseline, input.coverage(), input.questionnaire(), input.expected());
        }

        List<CaseSpec> normalized = deduplicate(cases).stream().limit(MAX_CASES).toList();
        String productId = text(input.delivery(), "productId");
        if (productId == null) productId = input.boundProductId();
        String productType = input.boundProductType();
        String strategy = strategy(input.requestedStrategy(), productType, techniques);

        List<String> blockers = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (productId == null) blockers.add("Bind an approved self-service product before launch.");
        if (input.topologyVersion() < 1) blockers.add("The Test Domain has no discovered topology version.");
        if (input.topologyHash() == null || input.topologyHash().isBlank()) {
            blockers.add("The Test Domain has no immutable topology fingerprint.");
        }
        if (normalized.size() >= MAX_CASES) {
            warnings.add("Coverage was capped at " + MAX_CASES + " cases. Narrow the parameter domains or split the Blueprint.");
        }
        if (!hasSemanticVerification(input.verification())) {
            warnings.add("No target predicate verification is configured; engine, reject, topology, and coverage checks will still run.");
        }

        Map<String, Object> coverage = new LinkedHashMap<>();
        coverage.put("techniques", techniques);
        coverage.put("caseCount", normalized.size());
        coverage.put("caseKinds", normalized.stream().collect(java.util.stream.Collectors.groupingBy(
                CaseSpec::kind, LinkedHashMap::new, java.util.stream.Collectors.counting())));
        coverage.put("requestedRows", input.requestedCount());
        coverage.put("warnings", warnings);

        List<Map<String, Object>> steps = new ArrayList<>();
        steps.add(step(1, "RESOLVE", "Resolve business intent",
                "Resolve approved terms, topology version, and Blueprint " + input.blueprintVersion() + ".", false));
        steps.add(step(2, "COVER", "Compose test coverage",
                "Build " + normalized.size() + " scenario cases using " + String.join(", ", techniques) + ".", false));
        steps.add(step(3, "SOURCE", "Select data strategy",
                "Use " + strategy + " through " + Objects.toString(productType, "an unbound product") + ".", false));
        steps.add(step(4, "PROTECT", "Apply privacy and integrity guardrails",
                "Use the linked product's masking, relationship, target, and volume controls.", false));
        steps.add(step(5, "DELIVER", "Deliver to " + Objects.toString(input.environment(), "the product default"),
                "Launch governed product " + Objects.toString(productId, "not yet bound") + ".", true));
        steps.add(step(6, "VERIFY", "Prove readiness",
                "Check execution success, rejects, topology compatibility, coverage retention, and configured predicates.", false));

        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("missionId", input.missionId());
        plan.put("domain", input.domainName());
        plan.put("topologyVersion", input.topologyVersion());
        plan.put("topologyHash", input.topologyHash());
        plan.put("blueprintVersion", input.blueprintVersion());
        plan.put("strategy", strategy);
        plan.put("productId", productId);
        plan.put("productType", productType);
        plan.put("environment", input.environment());
        plan.put("requestedRows", input.requestedCount());
        plan.put("caseCount", normalized.size());
        plan.put("steps", steps);
        plan.put("blockers", blockers);
        plan.put("warnings", warnings);
        plan.put("executable", blockers.isEmpty());

        return new Compilation(plan, coverage, normalized, blockers.isEmpty(), productId, strategy);
    }

    private void addBoundaries(List<CaseSpec> cases, Map<String, Object> baseline, JsonNode coverage,
                               JsonNode preconditions, JsonNode expected) {
        List<Boundary> boundaries = new ArrayList<>();
        JsonNode configured = coverage == null ? null : coverage.path("boundaries");
        if (configured != null && configured.isArray()) {
            for (JsonNode item : configured) {
                String field = text(item, "field");
                BigDecimal value = decimal(item.get("value"));
                if (field != null && value != null) boundaries.add(new Boundary(field, value));
            }
        }
        if (boundaries.isEmpty() && preconditions != null && preconditions.isArray()) {
            for (JsonNode item : preconditions) {
                String field = text(item, "field");
                BigDecimal value = decimal(item.get("value"));
                if (field != null && value != null) {
                    boundaries.add(new Boundary(field, value));
                    break;
                }
            }
        }
        for (Boundary boundary : boundaries) {
            for (int offset : List.of(-1, 0, 1)) {
                Map<String, Object> values = new LinkedHashMap<>(baseline);
                BigDecimal adjusted = boundary.value().add(BigDecimal.valueOf(offset));
                values.put(boundary.field(), adjusted.stripTrailingZeros());
                cases.add(caseSpec("boundary-" + slug(boundary.field()) + "-" + label(offset),
                        boundary.field() + " at boundary " + signed(offset), "BOUNDARY", values, expected));
            }
        }
    }

    private void addNegative(List<CaseSpec> cases, Map<String, Object> baseline,
                             JsonNode preconditions, JsonNode expected) {
        String field = null;
        if (preconditions != null && preconditions.isArray()) {
            for (JsonNode item : preconditions) {
                if (item.path("required").asBoolean(false)) {
                    field = text(item, "field");
                    if (field != null) break;
                }
            }
            if (field == null && !preconditions.isEmpty()) field = text(preconditions.get(0), "field");
        }
        Map<String, Object> values = new LinkedHashMap<>(baseline);
        if (field == null) values.put("_invalid", true);
        else values.put(field, null);
        cases.add(caseSpec("negative-required-value", field == null
                        ? "Invalid business-state candidate" : "Missing required " + field,
                "NEGATIVE", values, expected));
    }

    private void addPairwise(List<CaseSpec> cases, Map<String, Object> baseline, JsonNode coverage,
                             JsonNode questionnaire, JsonNode expected) {
        Map<String, List<Object>> parameters = optionDomains(coverage, questionnaire);
        if (parameters.size() < 2) return;
        List<Map<String, Object>> rows = pairwiseRows(parameters);
        int index = 1;
        for (Map<String, Object> row : rows) {
            Map<String, Object> values = new LinkedHashMap<>(baseline);
            values.putAll(row);
            cases.add(caseSpec("pairwise-" + index, "Pairwise combination " + index,
                    "PAIRWISE", values, expected));
            index++;
        }
    }

    private Map<String, List<Object>> optionDomains(JsonNode coverage, JsonNode questionnaire) {
        Map<String, List<Object>> out = new LinkedHashMap<>();
        JsonNode configured = coverage == null ? null : coverage.path("parameters");
        if (configured != null && configured.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = configured.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                List<Object> values = nodeList(field.getValue());
                if (values.size() > 1) out.put(field.getKey(), values);
            }
        }
        if (out.size() >= 2 || questionnaire == null || !questionnaire.isArray()) return out;
        for (JsonNode item : questionnaire) {
            String key = text(item, "key");
            List<Object> options = nodeList(item.path("options"));
            if (key != null && options.size() > 1) out.putIfAbsent(key, options);
        }
        return out;
    }

    private List<Map<String, Object>> pairwiseRows(Map<String, List<Object>> parameters) {
        List<String> names = new ArrayList<>(parameters.keySet());
        List<Map<String, Object>> candidates = new ArrayList<>();
        buildCartesian(names, parameters, 0, new LinkedHashMap<>(), candidates, 2048);

        Set<String> uncovered = new LinkedHashSet<>();
        for (int left = 0; left < names.size(); left++) {
            for (int right = left + 1; right < names.size(); right++) {
                String a = names.get(left);
                String b = names.get(right);
                for (Object av : parameters.get(a)) {
                    for (Object bv : parameters.get(b)) uncovered.add(pair(a, av, b, bv));
                }
            }
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        while (!uncovered.isEmpty() && !candidates.isEmpty() && selected.size() < MAX_CASES) {
            Map<String, Object> best = null;
            int bestScore = -1;
            for (Map<String, Object> candidate : candidates) {
                int score = pairs(candidate).stream().mapToInt(item -> uncovered.contains(item) ? 1 : 0).sum();
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
            if (best == null || bestScore < 1) break;
            selected.add(best);
            uncovered.removeAll(pairs(best));
            candidates.remove(best);
        }
        return selected;
    }

    private void buildCartesian(List<String> names, Map<String, List<Object>> parameters, int index,
                                Map<String, Object> current, List<Map<String, Object>> out, int max) {
        if (out.size() >= max) return;
        if (index == names.size()) {
            out.add(new LinkedHashMap<>(current));
            return;
        }
        String name = names.get(index);
        for (Object value : parameters.get(name)) {
            current.put(name, value);
            buildCartesian(names, parameters, index + 1, current, out, max);
            if (out.size() >= max) break;
        }
        current.remove(name);
    }

    private Set<String> pairs(Map<String, Object> row) {
        List<String> names = new ArrayList<>(row.keySet());
        Set<String> out = new LinkedHashSet<>();
        for (int left = 0; left < names.size(); left++) {
            for (int right = left + 1; right < names.size(); right++) {
                String a = names.get(left);
                String b = names.get(right);
                out.add(pair(a, row.get(a), b, row.get(b)));
            }
        }
        return out;
    }

    private static String pair(String a, Object av, String b, Object bv) {
        return a + "=" + Objects.toString(av) + "|" + b + "=" + Objects.toString(bv);
    }

    private Map<String, Object> baseline(JsonNode preconditions, Map<String, Object> parameters) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (preconditions != null && preconditions.isArray()) {
            for (JsonNode item : preconditions) {
                String field = text(item, "field");
                if (field != null) out.put(field, nodeValue(item.get("value")));
            }
        }
        if (parameters != null) out.putAll(parameters);
        return out;
    }

    private Set<String> techniques(JsonNode coverage) {
        Set<String> out = new LinkedHashSet<>();
        JsonNode values = coverage == null ? null : coverage.path("techniques");
        if (values != null && values.isArray()) {
            for (JsonNode value : values) {
                if (value.isTextual() && !value.asText().isBlank()) {
                    out.add(value.asText().trim().toUpperCase(Locale.ROOT));
                }
            }
        }
        out.add("BASELINE");
        return out;
    }

    private static String strategy(String requested, String productType, Set<String> techniques) {
        String value = requested == null ? "AUTO" : requested.trim().toUpperCase(Locale.ROOT);
        if (!"AUTO".equals(value)) return value;
        String type = Objects.toString(productType, "");
        return switch (type) {
            case "SYNTHETIC" -> "SYNTHETIC";
            case "DATASCOPE" -> techniques.contains("NEGATIVE") || techniques.contains("BOUNDARY")
                    ? "MASKED_SUBSET_WITH_CONTROLLED_VARIANTS" : "MASKED_SUBSET";
            case "VDB_PROVISION", "VDB_REFRESH", "VDB_ROLLBACK" -> "SNAPSHOT";
            case "RESERVATION" -> "RESERVED_EXISTING";
            case "MAPPING" -> "TRANSFORM";
            default -> "AUTO";
        };
    }

    private CaseSpec caseSpec(String key, String title, String kind,
                              Map<String, Object> inputs, JsonNode expected) {
        return new CaseSpec(key, title, kind, inputs, nodeValue(expected));
    }

    private List<CaseSpec> deduplicate(List<CaseSpec> input) {
        Map<String, CaseSpec> unique = new LinkedHashMap<>();
        for (CaseSpec item : input) {
            String fingerprint;
            try {
                fingerprint = item.kind() + "|" + json.writeValueAsString(item.inputs());
            } catch (Exception ignored) {
                fingerprint = item.kind() + "|" + item.inputs();
            }
            unique.putIfAbsent(fingerprint, item);
        }
        return new ArrayList<>(unique.values());
    }

    private static Map<String, Object> step(int ordinal, String code, String title,
                                            String detail, boolean changesData) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ordinal", ordinal);
        out.put("code", code);
        out.put("title", title);
        out.put("detail", detail);
        out.put("changesData", changesData);
        return out;
    }

    private boolean hasSemanticVerification(JsonNode verification) {
        return verification != null && verification.path("predicates").isArray()
                && !verification.path("predicates").isEmpty();
    }

    private Object nodeValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        return json.convertValue(node, Object.class);
    }

    private List<Object> nodeList(JsonNode node) {
        if (node == null || !node.isArray()) return List.of();
        List<Object> out = new ArrayList<>();
        for (JsonNode value : node) out.add(nodeValue(value));
        return out;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String value = node.path(field).asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static BigDecimal decimal(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return node.isNumber() ? node.decimalValue() : new BigDecimal(node.asText());
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String slug(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String label(int offset) {
        return offset < 0 ? "below" : offset > 0 ? "above" : "exact";
    }

    private static String signed(int offset) {
        return offset > 0 ? "+" + offset : String.valueOf(offset);
    }

    private record Boundary(String field, BigDecimal value) {}

    public record Input(String missionId, String domainName, int topologyVersion, String topologyHash,
                        int blueprintVersion, JsonNode preconditions, JsonNode event, JsonNode expected,
                        JsonNode coverage, JsonNode delivery, JsonNode questionnaire, JsonNode verification,
                        Map<String, Object> parameters, String requestedStrategy, long requestedCount,
                        String environment, String boundProductId, String boundProductType) {}

    public record CaseSpec(String key, String title, String kind, Map<String, Object> inputs,
                           Object expected) {}

    public record Compilation(Map<String, Object> plan, Map<String, Object> coverage,
                              List<CaseSpec> cases, boolean executable, String productId,
                              String strategy) {}
}
