package io.forgetdm.testdata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskContext;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingEngine;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.testdata.TdDto.Plan;
import io.forgetdm.testdata.TdDto.PlanAsset;
import io.forgetdm.testdata.TdDto.ProvisionedObject;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Generic, catalog-driven provisioner. Each recipe's {@code backing_json} describes its table, id,
 * business key, columns and link — so this class provisions <em>any</em> asset the catalog defines
 * (no per-asset code). Records land in a self-contained {@code selfservice} schema, linked to the
 * anchor (Customer). Synthetic and deterministic-per-request.
 */
@Component
public class TdProvisioner {

    private static final String SCHEMA = "selfservice";
    private final ConnectionFactory connections;
    private final MaskingEngine masking;
    private final ObjectMapper json;

    public TdProvisioner(ConnectionFactory connections, MaskingEngine masking, ObjectMapper json) {
        this.connections = connections;
        this.masking = masking;
        this.json = json;
    }

    public record Result(List<ProvisionedObject> objects, String locatorColumn, String locatorValue,
                         String schema, String anchorTable) {}

    public Result provision(TdRequestEntity request, Plan plan, List<TdRecipeEntity> recipes, DataSourceEntity target) {
        Map<String, Map<String, Object>> backings = backings(recipes);
        Map<String, TdRecipeEntity> byKey = new LinkedHashMap<>();
        recipes.forEach(r -> byKey.put(r.getRecipeKey(), r));
        TdRecipeEntity anchorRecipe = recipes.stream().filter(TdRecipeEntity::isAnchor).findFirst().orElse(null);
        Map<String, Object> anchorBacking = anchorRecipe == null ? null : backings.get(anchorRecipe.getRecipeKey());

        List<ProvisionedObject> objects = new ArrayList<>();
        Random rng = new Random(request.getId() == null ? System.nanoTime() : request.getId());
        String locatorColumn = anchorBacking == null ? "id" : bkColumn(anchorBacking);
        String locatorValue = null;

        try (Connection c = connections.openForBulk(target)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) { st.execute("CREATE SCHEMA IF NOT EXISTS " + SCHEMA); }

            int sets = Math.max(1, request.getQuantity());
            for (int set = 0; set < sets; set++) {
                Long anchorId = null;
                String anchorPrefix = anchorBacking == null ? "C" : str(anchorBacking.get("idPrefix"));
                PlanAsset anchorAsset = find(plan, anchorRecipe == null ? null : anchorRecipe.getRecipeKey());
                if (anchorAsset != null && anchorBacking != null) {
                    ensureTable(c, anchorBacking, null);
                    Inserted ins = insert(c, anchorBacking, null, Map.of(), rng, set);
                    anchorId = ins.id;
                    if (locatorValue == null) locatorValue = ins.businessKeyValue;
                    objects.add(new ProvisionedObject(anchorRecipe.getName(),
                            anchorPrefix + "-" + ins.id, ins.label, ins.display, null));
                }
                for (PlanAsset a : plan.assets()) {
                    if (anchorRecipe != null && a.recipeKey().equals(anchorRecipe.getRecipeKey())) continue;
                    Map<String, Object> b = backings.get(a.recipeKey());
                    if (b == null) continue;
                    ensureTable(c, b, anchorBacking);
                    Inserted ins = insert(c, b, anchorId, a.attributes(), rng, set);
                    objects.add(new ProvisionedObject(byKey.get(a.recipeKey()).getName(),
                            str(b.get("idPrefix")) + "-" + ins.id, ins.label, ins.display,
                            anchorId == null ? null : anchorPrefix + "-" + anchorId));
                }
            }
            c.commit();
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Provisioning failed: " + rootMessage(e));
        }
        return new Result(objects, locatorColumn, locatorValue, SCHEMA,
                anchorBacking == null ? "sst_customer" : str(anchorBacking.get("table")));
    }

    public int teardown(DataSourceEntity target, List<TdRecipeEntity> recipes, List<Long> anchorIds) {
        if (anchorIds == null || anchorIds.isEmpty()) return 0;
        Map<String, Map<String, Object>> backings = backings(recipes);
        TdRecipeEntity anchorRecipe = recipes.stream().filter(TdRecipeEntity::isAnchor).findFirst().orElse(null);
        String in = String.join(",", anchorIds.stream().map(String::valueOf).toList());
        int removed = 0;
        try (Connection c = connections.openForBulk(target)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                for (var e : backings.entrySet()) {
                    Map<String, Object> b = e.getValue();
                    if (anchorRecipe != null && e.getKey().equals(anchorRecipe.getRecipeKey())) continue;
                    Map<String, Object> link = asMap(b.get("link"));
                    if (link == null) continue;
                    st.executeUpdate("DELETE FROM " + SCHEMA + "." + str(b.get("table"))
                            + " WHERE " + str(link.get("column")) + " IN (" + in + ")");
                }
                if (anchorRecipe != null) {
                    Map<String, Object> ab = backings.get(anchorRecipe.getRecipeKey());
                    removed = st.executeUpdate("DELETE FROM " + SCHEMA + "." + str(ab.get("table"))
                            + " WHERE " + str(ab.get("idColumn")) + " IN (" + in + ")");
                }
            }
            c.commit();
        } catch (Exception e) {
            throw ApiException.bad("Teardown failed: " + rootMessage(e));
        }
        return removed;
    }

    // ------------------------------------------------------------------ DDL + insert (generic)

    private void ensureTable(Connection c, Map<String, Object> b, Map<String, Object> anchor) throws Exception {
        StringBuilder ddl = new StringBuilder("CREATE TABLE IF NOT EXISTS " + SCHEMA + "." + str(b.get("table")) + " (");
        ddl.append(str(b.get("idColumn"))).append(" BIGSERIAL PRIMARY KEY");
        Map<String, Object> bk = asMap(b.get("businessKey"));
        if (bk != null) ddl.append(", ").append(str(bk.get("column"))).append(" ").append(str(bk.get("type"))).append(" UNIQUE");
        Map<String, Object> link = asMap(b.get("link"));
        if (link != null && anchor != null) {
            ddl.append(", ").append(str(link.get("column"))).append(" BIGINT REFERENCES ")
               .append(SCHEMA).append(".").append(str(anchor.get("table")))
               .append("(").append(str(anchor.get("idColumn"))).append(")");
        }
        for (Map<String, Object> col : columns(b)) {
            ddl.append(", ").append(str(col.get("column"))).append(" ").append(str(col.get("type")));
            String gen = str(col.get("gen"));
            if ("today".equals(gen)) ddl.append(" DEFAULT current_date");
            else if ("now".equals(gen)) ddl.append(" DEFAULT now()");
        }
        ddl.append(")");
        try (Statement st = c.createStatement()) { st.execute(ddl.toString()); }
    }

    private record Inserted(long id, String businessKeyValue, String label, Map<String, String> display) {}

    private Inserted insert(Connection c, Map<String, Object> b, Long anchorId,
                            Map<String, String> planAttrs, Random rng, int set) throws Exception {
        List<String> cols = new ArrayList<>();
        List<String> exprs = new ArrayList<>();
        List<String> params = new ArrayList<>();
        Map<String, String> display = new LinkedHashMap<>();

        Map<String, Object> bk = asMap(b.get("businessKey"));
        String bkValue = null;
        if (bk != null) {
            bkValue = genValue(str(bk.get("gen")), rng, set);
            cols.add(str(bk.get("column")));
            exprs.add("CAST(? AS " + str(bk.get("type")) + ")");
            params.add(bkValue);
            if (bk.get("label") != null) display.put(str(bk.get("label")), bkValue);
        }
        Map<String, Object> link = asMap(b.get("link"));
        if (link != null && anchorId != null) {
            cols.add(str(link.get("column")));
            exprs.add("CAST(? AS bigint)");
            params.add(String.valueOf(anchorId));
        }
        String label = bkValue;
        for (Map<String, Object> col : columns(b)) {
            String gen = str(col.get("gen"));
            if ("today".equals(gen) || "now".equals(gen)) continue;   // DB default
            String value;
            if (gen != null) {
                value = genValue(gen, rng, set);
                if ("fullname".equals(gen)) label = value;            // the human label of the record
            } else {
                String from = str(col.get("from"));
                value = from != null && planAttrs.containsKey(from) ? planAttrs.get(from) : str(col.get("default"));
            }
            if (value == null) continue;
            cols.add(str(col.get("column")));
            exprs.add("CAST(? AS " + str(col.get("type")) + ")");
            params.add(value);
            String unit = str(col.get("unit"));
            display.put(prettyLabel(str(col.get("column"))), unit == null ? value : value + " " + unit);
        }

        String sql = "INSERT INTO " + SCHEMA + "." + str(b.get("table")) + " (" + String.join(", ", cols)
                + ") VALUES (" + String.join(", ", exprs) + ") RETURNING " + str(b.get("idColumn"));
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Inserted(rs.getLong(1), bkValue, label == null ? bkValue : label, display);
            }
        }
    }

    // ------------------------------------------------------------------ value generation

    private String genValue(String gen, Random rng, int set) {
        if (gen == null) return null;
        if (gen.equals("fullname")) {
            return masking.mask(MaskFunction.FULL_NAME, "td.name", "seed-" + rng.nextInt(1_000_000), null, null,
                    new MaskContext(set));
        }
        if (gen.startsWith("const:")) return gen.substring("const:".length());
        if (gen.startsWith("digits:")) return digits(rng, parseInt(gen.substring("digits:".length()), 8));
        if (gen.startsWith("prefixdigits:")) {
            String[] p = gen.split(":");
            String prefix = p.length > 1 ? p[1] : "X";
            int n = p.length > 2 ? parseInt(p[2], 8) : 8;
            return prefix + "-" + digits(rng, n);
        }
        return null;
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Map<String, Object>> backings(List<TdRecipeEntity> recipes) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (TdRecipeEntity r : recipes) {
            Map<String, Object> b = parseBacking(r.getBackingJson());
            if (b != null) out.put(r.getRecipeKey(), b);
        }
        return out;
    }

    private Map<String, Object> parseBacking(String j) {
        if (j == null || j.isBlank()) return null;
        try { return json.readValue(j, new TypeReference<Map<String, Object>>() {}); }
        catch (Exception e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> columns(Map<String, Object> b) {
        Object cols = b.get("columns");
        return cols instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : null; }

    private String bkColumn(Map<String, Object> anchor) {
        Map<String, Object> bk = asMap(anchor.get("businessKey"));
        return bk == null ? "id" : str(bk.get("column"));
    }

    private static PlanAsset find(Plan plan, String key) {
        if (key == null) return null;
        return plan.assets().stream().filter(a -> key.equals(a.recipeKey())).findFirst().orElse(null);
    }

    private static String digits(Random rng, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(rng.nextInt(10));
        return sb.toString();
    }

    private static String prettyLabel(String column) {
        String s = column.replace('_', ' ').trim();
        return s.isEmpty() ? column : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static int parseInt(String s, int dflt) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return dflt; }
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String rootMessage(Throwable e) {
        Throwable r = e;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.getMessage() == null ? r.toString() : r.getMessage();
    }
}
