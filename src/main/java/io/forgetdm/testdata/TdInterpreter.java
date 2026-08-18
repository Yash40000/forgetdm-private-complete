package io.forgetdm.testdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.testdata.TdDto.Plan;
import io.forgetdm.testdata.TdDto.PlanAsset;
import io.forgetdm.testdata.TdDto.Safety;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fully catalog-driven plain-language interpreter. It matches recipes by their keywords and extracts
 * attribute values generically from each recipe's typed attribute vocabulary (money / number / enum /
 * text) — there is no asset-specific logic here, so adding a new asset is a data change, not code.
 * Deterministic and dependency-free; ambiguity or an unknown request becomes a helpful question that
 * lists what the catalog can actually create.
 */
@Component
public class TdInterpreter {

    private static final Pattern MONEY = Pattern.compile("\\$?\\s?([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s?([kmKM])?");
    private final ObjectMapper json;

    public TdInterpreter(ObjectMapper json) { this.json = json; }

    public Plan interpret(String text, List<TdRecipeEntity> recipes, String environment, int quantity) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);

        TdRecipeEntity anchor = recipes.stream().filter(TdRecipeEntity::isAnchor).findFirst().orElse(null);
        List<TdRecipeEntity> matched = new ArrayList<>();
        for (TdRecipeEntity r : recipes) {
            if (r.isAnchor()) continue;
            if (matchesAny(lower, r.getKeywords())) matched.add(r);
        }

        List<PlanAsset> assets = new ArrayList<>();
        List<String> summaryParts = new ArrayList<>();
        List<String> open = new ArrayList<>();

        boolean anchorMentioned = anchor != null && matchesAny(lower, anchor.getKeywords());
        boolean needAnchor = anchor != null && (!matched.isEmpty() || anchorMentioned);
        if (needAnchor) {
            assets.add(new PlanAsset(anchor.getRecipeKey(), anchor.getName(), 1, new LinkedHashMap<>(), null));
            summaryParts.add("a " + anchor.getName().toLowerCase(Locale.ROOT));
        }

        String anchorName = anchor == null ? null : anchor.getName();
        for (TdRecipeEntity r : matched) {
            Extraction ex = extract(r, lower);
            assets.add(new PlanAsset(r.getRecipeKey(), r.getName(), 1, ex.values, needAnchor ? anchorName : null));
            summaryParts.add("a " + r.getName().toLowerCase(Locale.ROOT) + ex.summaryFragment);
        }

        if (assets.isEmpty()) {
            open.add("I don't have a recipe for that yet. Right now I can create: "
                    + catalogNames(recipes) + ". Try naming one of those — or an admin can add a new asset.");
        }

        String summary = summarise(summaryParts, quantity);
        Safety safety = new Safety("SYNTHETIC", true, false); // synthetic → instant, no approval
        int seconds = 10 + assets.size() * 8;
        return new Plan(summary, environment, quantity, assets, safety, open, seconds);
    }

    // ------------------------------------------------------------------ generic attribute extraction

    private record Extraction(Map<String, String> values, String summaryFragment) {}

    @SuppressWarnings("unchecked")
    private Extraction extract(TdRecipeEntity recipe, String lower) {
        Map<String, String> values = new LinkedHashMap<>();
        List<Map<String, Object>> defs = parseAttrs(recipe.getAttributesJson());
        String moneyFrag = null, enumFrag = null;

        for (Map<String, Object> def : defs) {
            String name = str(def.get("name"));
            String type = str(def.get("type"));
            String dflt = str(def.get("default"));
            if (name == null) continue;

            List<String> syns = new ArrayList<>();
            Object s = def.get("synonyms");
            if (s instanceof List<?> l) for (Object o : l) syns.add(String.valueOf(o).toLowerCase(Locale.ROOT));
            syns.add(name.toLowerCase(Locale.ROOT));
            String label = str(def.get("label"));
            if (label != null) syns.add(label.toLowerCase(Locale.ROOT));
            for (String w : recipe.getName().toLowerCase(Locale.ROOT).split("\\s+")) if (w.length() > 2) syns.add(w);

            String value;
            if ("money".equalsIgnoreCase(type) || "number".equalsIgnoreCase(type)) {
                String found = amountNear(lower, syns);
                value = found != null ? found : (dflt != null ? dflt : "0");
                // Only headline an amount the tester actually stated (not a silent default).
                if ("money".equalsIgnoreCase(type) && moneyFrag == null && found != null && !isZero(value)) {
                    moneyFrag = " with a $" + trimMoney(value) + (label != null ? " " + label.toLowerCase(Locale.ROOT) : "");
                }
            } else if ("enum".equalsIgnoreCase(type)) {
                value = enumMatch(lower, (List<Object>) def.getOrDefault("options", List.of()));
                if (value == null) value = dflt;
                if (enumFrag == null && value != null) enumFrag = " (" + value.toLowerCase(Locale.ROOT) + ")";
            } else {
                value = dflt;
            }
            if (value != null) values.put(name, value);
        }

        String frag = moneyFrag != null ? moneyFrag : (enumFrag != null ? enumFrag : "");
        return new Extraction(values, frag);
    }

    private String enumMatch(String lower, List<Object> options) {
        for (Object o : options) {
            String opt = String.valueOf(o);
            if (containsWord(lower, opt.toLowerCase(Locale.ROOT))) return opt.toUpperCase(Locale.ROOT);
        }
        return null;
    }

    /** The numeric amount nearest one of the given anchor words (money or plain number). */
    private String amountNear(String lower, List<String> anchors) {
        int anchorPos = -1;
        for (String a : anchors) {
            int p = lower.indexOf(a);
            if (p >= 0 && (anchorPos < 0 || p < anchorPos)) anchorPos = p;
        }
        Matcher m = MONEY.matcher(lower);
        String best = null;
        int bestDist = Integer.MAX_VALUE;
        while (m.find()) {
            String num = m.group(1);
            if (num == null || num.replace(",", "").matches("0+")) continue;
            int dist = anchorPos < 0 ? m.start() : Math.abs(m.start() - anchorPos);
            if (dist < bestDist) { bestDist = dist; best = normaliseMoney(num, m.group(2)); }
        }
        return best;
    }

    private String normaliseMoney(String num, String suffix) {
        try {
            BigDecimal v = new BigDecimal(num.replace(",", ""));
            if (suffix != null) {
                String s = suffix.toLowerCase(Locale.ROOT);
                if (s.equals("k")) v = v.multiply(BigDecimal.valueOf(1_000));
                else if (s.equals("m")) v = v.multiply(BigDecimal.valueOf(1_000_000));
            }
            return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ helpers

    private List<Map<String, Object>> parseAttrs(String jsonStr) {
        if (jsonStr == null || jsonStr.isBlank()) return List.of();
        try { return json.readValue(jsonStr, new TypeReference<List<Map<String, Object>>>() {}); }
        catch (Exception e) { return List.of(); }
    }

    private boolean matchesAny(String lower, String keywordsCsv) {
        if (keywordsCsv == null) return false;
        for (String kw : keywordsCsv.split(",")) {
            String k = kw.trim().toLowerCase(Locale.ROOT);
            if (!k.isEmpty() && lower.contains(k)) return true;
        }
        return false;
    }

    private boolean containsWord(String lower, String word) {
        return Pattern.compile("\\b" + Pattern.quote(word) + "\\b").matcher(lower).find();
    }

    private String catalogNames(List<TdRecipeEntity> recipes) {
        List<String> names = recipes.stream().map(TdRecipeEntity::getName).toList();
        if (names.size() <= 1) return String.join(", ", names);
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private String summarise(List<String> parts, int quantity) {
        if (parts.isEmpty()) return "Nothing recognised yet.";
        String set = String.join(", ", parts).replaceFirst(", ([^,]*)$", " and $1");
        String qty = quantity > 1 ? quantity + " sets of " : "";
        return "Create " + qty + set + ".";
    }

    private static boolean isZero(String v) {
        try { return new BigDecimal(v).signum() == 0; } catch (Exception e) { return false; }
    }

    private static String trimMoney(String m) {
        return m.endsWith(".00") ? m.substring(0, m.length() - 3) : m;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }
}
