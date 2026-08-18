package io.forgetdm.compliance;

import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.discovery.ClassificationEntity;
import io.forgetdm.discovery.ClassificationRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The three checks that turn "we masked it, trust us" into evidence.
 *
 * <ol>
 *   <li><b>Coverage</b> — every column classified as PII must have a masking rule. A field nobody
 *       wrote a rule for is the most common real-world leak, and it is invisible to any check that
 *       only inspects columns it already knows about.</li>
 *   <li><b>Leak</b> — proof of <em>absence</em>, over whole columns rather than a sample:
 *       <ul>
 *         <li><i>pattern scan</i> — does any value in the target still validate as a real-world
 *             identifier (Luhn-valid card, mod-97 IBAN, issuable SSN, deliverable email)? Needs no
 *             production access at all, so it can run in environments where prod is unreachable.</li>
 *         <li><i>hashed match</i> — build a salted-hash set of the source column and look for any
 *             target value that collides. Exact detection of a surviving production value, with no
 *             raw PII crossing into the evidence.</li>
 *       </ul></li>
 *   <li><b>Cardinality</b> — did masking collapse a column to one (or very few) distinct values?
 *       That is what lets a broken tenant filter pass every test: if every customer's account number
 *       is identical, joins "work" and cross-customer bugs never surface.</li>
 * </ol>
 *
 * Scans are read-only and row-capped so an auditor can run them against a live environment.
 */
@Component
public class ComplianceScanner {

    /** Hard cap on rows read per column, so a scan can never become an outage. */
    static final int MAX_ROWS_PER_COLUMN = 2_000_000;
    /** Fetch size for streaming reads. */
    private static final int FETCH = 5_000;
    /** Below this distinct/row ratio a masked column is treated as collapsed. */
    private static final double CARDINALITY_FAIL_RATIO = 0.10;
    private static final double CARDINALITY_WARN_RATIO = 0.50;
    /** Columns with fewer rows than this are too small to judge cardinality on. */
    private static final int CARDINALITY_MIN_ROWS = 50;

    private final ClassificationRepository classifications;
    private final MaskingRuleRepository rules;
    private final ConnectionFactory connections;
    private final ComplianceHasher hasher;

    public ComplianceScanner(ClassificationRepository classifications, MaskingRuleRepository rules,
                             ConnectionFactory connections, ComplianceHasher hasher) {
        this.classifications = classifications;
        this.rules = rules;
        this.connections = connections;
        this.hasher = hasher;
    }

    /** A finding produced by a scanner, before it is persisted. */
    public record Finding(String severity, String check, String schema, String table, String column,
                          String piiType, long affectedRows, String detail, String remediation,
                          String evidenceHash) {
        static Finding fail(String check, Target t, String piiType, long rows, String detail, String fix, String hash) {
            return new Finding("FAIL", check, t.schema(), t.table(), t.column(), piiType, rows, detail, fix, hash);
        }
        static Finding warn(String check, Target t, String piiType, long rows, String detail, String fix) {
            return new Finding("WARN", check, t.schema(), t.table(), t.column(), piiType, rows, detail, fix, null);
        }
        static Finding info(String check, String detail) {
            return new Finding("INFO", check, null, null, null, null, 0, detail, null, null);
        }
    }

    /** A column under scan. */
    public record Target(String schema, String table, String column) {
        String label() {
            return (schema == null || schema.isBlank() ? "" : schema + ".") + table + "." + column;
        }
    }

    /** Aggregate outcome of one scanner pass. */
    public record Outcome(List<Finding> findings, int columnsScanned, long rowsScanned) {}

    // ================================================================= coverage

    /**
     * Report every column classified as PII on {@code source} that no rule in {@code policyId} masks.
     *
     * <p>Confirmed classifications are hard failures — someone looked at that column and agreed it
     * holds PII. Merely suggested ones are warnings, because triage may still reject them; but they
     * are surfaced rather than dropped, since "we never reviewed it" is not a defence to an auditor.
     */
    public Outcome scanCoverage(DataSourceEntity source, String schemaName, Long policyId) {
        List<ClassificationEntity> found = schemaName == null || schemaName.isBlank()
                ? classifications.findByDataSourceId(source.getId())
                : classifications.findByDataSourceIdAndSchemaName(source.getId(), schemaName);

        List<MaskingRuleEntity> policyRules = policyId == null ? List.of() : rules.findByPolicyId(policyId);
        Set<String> covered = new HashSet<>();
        for (MaskingRuleEntity r : policyRules) covered.add(key(r.getTableName(), r.getColumnName()));

        List<Finding> findings = new ArrayList<>();
        int piiColumns = 0;
        for (ClassificationEntity c : found) {
            if ("REJECTED".equalsIgnoreCase(c.getStatus()) || "IGNORED".equalsIgnoreCase(c.getStatus())) continue;
            piiColumns++;
            if (covered.contains(key(c.getTableName(), c.getColumnName()))) continue;

            Target t = new Target(c.getSchemaName(), c.getTableName(), c.getColumnName());
            boolean confirmed = "CONFIRMED".equalsIgnoreCase(c.getStatus()) || "ACCEPTED".equalsIgnoreCase(c.getStatus());
            String fix = c.getSuggestedFunction() == null
                    ? "Add a masking rule for this column to the policy, or reject the classification with a reason."
                    : "Add a masking rule using " + c.getSuggestedFunction() + " (the discovery suggestion) to the policy.";
            String status = c.getStatus() == null ? "unreviewed" : c.getStatus().toLowerCase(Locale.ROOT);
            String detail = t.label() + " is classified as " + c.getPiiType()
                    + " (" + status + ", confidence "
                    + Math.round(c.getConfidence() * 100) + "%) but no rule in this policy masks it";
            findings.add(confirmed
                    ? Finding.fail("COVERAGE", t, c.getPiiType(), 0, detail, fix, null)
                    : Finding.warn("COVERAGE", t, c.getPiiType(), 0, detail, fix));
        }

        if (policyId == null) {
            findings.add(Finding.info("COVERAGE",
                    "No policy supplied — every classified PII column is reported as uncovered by definition."));
        } else if (findings.isEmpty()) {
            findings.add(Finding.info("COVERAGE", "All " + piiColumns
                    + " classified PII column(s) are covered by a masking rule in this policy."));
        }
        return new Outcome(findings, piiColumns, 0);
    }

    // ===================================================================== leak

    /**
     * Prove no real production PII survives in {@code target}.
     *
     * <p>Scans the whole of every column that either has a masking rule or is classified as PII.
     * When {@code source} is supplied, source values are additionally hashed into a set and every
     * target value checked against it, which catches a surviving production value even when that
     * value is not of a checksum-verifiable type (a name, an address).
     */
    public Outcome scanLeaks(DataSourceEntity target, DataSourceEntity source, String schemaName, Long policyId) {
        Map<Target, String> columns = columnsToScan(target, source, schemaName, policyId);
        if (columns.isEmpty()) {
            return new Outcome(List.of(Finding.info("LEAK_PATTERN",
                    "Nothing to scan: no masking rules and no PII classifications for this target.")), 0, 0);
        }

        List<Finding> findings = new ArrayList<>();
        long rowsScanned = 0;
        int scanned = 0;

        try (Connection out = connections.open(target);
             Connection in = source == null ? null : connections.open(source)) {

            for (Map.Entry<Target, String> entry : columns.entrySet()) {
                Target t = entry.getKey();
                String piiType = entry.getValue();
                Target resolved = resolve(out, t);
                if (!columnExists(out, resolved)) continue;

                // --- 1) hashed source set (exact detection, no raw PII retained) ---
                Set<String> sourceHashes = null;
                if (in != null && columnExists(in, resolve(in, t))) {
                    sourceHashes = hashColumn(in, resolve(in, t));
                }

                ColumnScan result = scanColumn(out, resolved, piiType, sourceHashes);
                rowsScanned += result.rows();
                scanned++;

                if (result.matchHits() > 0) {
                    findings.add(Finding.fail("LEAK_MATCH", resolved, piiType, result.matchHits(),
                            result.matchHits() + " value(s) in " + resolved.label()
                                    + " are identical to a value in the production source — masking did not take effect here",
                            "Re-run masking for this column and confirm the rule is bound to it; then re-scan.",
                            result.matchWitness()));
                }
                if (result.patternHits() > 0) {
                    findings.add(Finding.fail("LEAK_PATTERN", resolved, piiType, result.patternHits(),
                            result.patternHits() + " value(s) in " + resolved.label() + " are a "
                                    + PiiRealityCheck.reason(piiType)
                                    + " — real-world-valid identifiers should not exist in a masked environment",
                            "Mask this column with a format-preserving function that does not emit checksum-valid"
                                    + " real values, or register an approved exception if this is intentional.",
                            result.patternWitness()));
                }
                if (result.matchHits() == 0 && result.patternHits() == 0 && result.rows() > 0) {
                    findings.add(new Finding("INFO", sourceHashes == null ? "LEAK_PATTERN" : "LEAK_MATCH",
                            resolved.schema(), resolved.table(), resolved.column(), piiType, result.rows(),
                            resolved.label() + ": " + result.rows() + " row(s) scanned, no real PII found"
                                    + (sourceHashes == null ? " (pattern scan)" : " and no source-value collisions"),
                            null, null));
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Leak scan failed: " + e.getMessage());
        }
        return new Outcome(findings, scanned, rowsScanned);
    }

    private record ColumnScan(long rows, long patternHits, String patternWitness,
                              long matchHits, String matchWitness) {}

    /** Stream one column once, applying both the pattern check and the source-hash check. */
    private ColumnScan scanColumn(Connection c, Target t, String piiType, Set<String> sourceHashes)
            throws SQLException {
        boolean checkPattern = PiiRealityCheck.supports(piiType);
        if (!checkPattern && sourceHashes == null) return new ColumnScan(0, 0, null, 0, null);

        long rows = 0, patternHits = 0, matchHits = 0;
        String patternWitness = null, matchWitness = null;

        try (Statement st = c.createStatement()) {
            st.setFetchSize(FETCH);
            st.setMaxRows(MAX_ROWS_PER_COLUMN);
            try (ResultSet rs = st.executeQuery(selectColumn(t))) {
                while (rs.next()) {
                    String v = rs.getString(1);
                    rows++;
                    if (v == null || v.isBlank()) continue;
                    if (checkPattern && PiiRealityCheck.looksReal(piiType, v)) {
                        patternHits++;
                        if (patternWitness == null) patternWitness = hasher.witness(v);
                    }
                    if (sourceHashes != null) {
                        String h = hasher.hash(v);
                        if (h != null && sourceHashes.contains(h)) {
                            matchHits++;
                            if (matchWitness == null) matchWitness = h.substring(0, 16);
                        }
                    }
                }
            }
        }
        return new ColumnScan(rows, patternHits, patternWitness, matchHits, matchWitness);
    }

    /** Salted-hash set of a source column. Raw values are never retained beyond the loop body. */
    private Set<String> hashColumn(Connection c, Target t) throws SQLException {
        Set<String> hashes = new HashSet<>();
        try (Statement st = c.createStatement()) {
            st.setFetchSize(FETCH);
            st.setMaxRows(MAX_ROWS_PER_COLUMN);
            try (ResultSet rs = st.executeQuery(selectColumn(t))) {
                while (rs.next()) {
                    String h = hasher.hash(rs.getString(1));
                    if (h != null) hashes.add(h);
                }
            }
        }
        return hashes;
    }

    // ============================================================== cardinality

    /**
     * Detect masking that collapsed a column's distinct values. Uses a single
     * {@code COUNT(*) / COUNT(DISTINCT col)} per column, so it is cheap even on large tables.
     */
    public Outcome scanCardinality(DataSourceEntity target, String schemaName, Long policyId) {
        Map<Target, String> columns = columnsToScan(target, null, schemaName, policyId);
        if (columns.isEmpty()) {
            return new Outcome(List.of(Finding.info("CARDINALITY",
                    "Nothing to scan: no masking rules and no PII classifications for this target.")), 0, 0);
        }

        List<Finding> findings = new ArrayList<>();
        int scanned = 0;
        long rowsSeen = 0;

        try (Connection out = connections.open(target)) {
            for (Map.Entry<Target, String> entry : columns.entrySet()) {
                Target t = resolve(out, entry.getKey());
                if (!columnExists(out, t)) continue;

                long[] counts = counts(out, t);
                long total = counts[0], distinct = counts[1];
                scanned++;
                rowsSeen += total;
                if (total < CARDINALITY_MIN_ROWS) continue;

                double ratio = (double) distinct / (double) total;
                if (distinct <= 1) {
                    findings.add(Finding.fail("CARDINALITY", t, entry.getValue(), total,
                            t.label() + " collapsed to " + distinct + " distinct value(s) across " + total
                                    + " rows — every row is identical, so any per-record or cross-tenant defect"
                                    + " is untestable in this environment",
                            "Use a deterministic, value-dependent masking function (not a constant or literal)"
                                    + " so distinct inputs produce distinct outputs.", null));
                } else if (ratio < CARDINALITY_FAIL_RATIO) {
                    findings.add(Finding.fail("CARDINALITY", t, entry.getValue(), total,
                            t.label() + " has only " + distinct + " distinct value(s) across " + total
                                    + " rows (" + pct(ratio) + ") — masking has severely reduced cardinality",
                            "Check the masking function's output space; a too-small pool or a truncated"
                                    + " format will alias many inputs onto few outputs.", null));
                } else if (ratio < CARDINALITY_WARN_RATIO) {
                    findings.add(Finding.warn("CARDINALITY", t, entry.getValue(), total,
                            t.label() + " has " + distinct + " distinct value(s) across " + total
                                    + " rows (" + pct(ratio) + ") — lower than expected for an identifier",
                            "Confirm this column is genuinely low-cardinality (a status or code) rather than"
                                    + " an identifier that masking has aliased."));
                }

                // Uniqueness: if the column is a declared key, any duplicate is a hard failure.
                if (isUniqueKey(out, t) && distinct < total) {
                    findings.add(Finding.fail("UNIQUENESS", t, entry.getValue(), total - distinct,
                            t.label() + " is a primary/unique key but masking produced " + (total - distinct)
                                    + " duplicate value(s) — colliding keys merge distinct records",
                            "Mask keys with a bijective, format-preserving function (FPE) so distinct inputs"
                                    + " can never collide.", null));
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Cardinality scan failed: " + e.getMessage());
        }

        if (findings.isEmpty()) {
            findings.add(Finding.info("CARDINALITY",
                    "All " + scanned + " scanned column(s) retained healthy distinct-value counts."));
        }
        return new Outcome(findings, scanned, rowsSeen);
    }

    private long[] counts(Connection c, Target t) throws SQLException {
        String sql = "SELECT COUNT(" + q(t.column()) + "), COUNT(DISTINCT " + q(t.column()) + ") FROM "
                + qualified(t);
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? new long[]{rs.getLong(1), rs.getLong(2)} : new long[]{0, 0};
        }
    }

    // =================================================================== shared

    /**
     * The columns a target scan should cover: every column with a masking rule in the policy, plus
     * every column classified as PII on the source (or, failing that, on the target itself). Using
     * the union matters — a rule without a classification and a classification without a rule are
     * both real situations, and both need scanning.
     */
    private Map<Target, String> columnsToScan(DataSourceEntity target, DataSourceEntity source,
                                              String schemaName, Long policyId) {
        Map<Target, String> out = new LinkedHashMap<>();

        if (policyId != null) {
            for (MaskingRuleEntity r : rules.findByPolicyId(policyId)) {
                out.putIfAbsent(new Target(firstText(r.getSchemaName(), schemaName), r.getTableName(),
                        r.getColumnName()), piiTypeForFunction(r.getFunction()));
            }
        }

        Long classificationSourceId = source != null ? source.getId() : target.getId();
        List<ClassificationEntity> found = schemaName == null || schemaName.isBlank()
                ? classifications.findByDataSourceId(classificationSourceId)
                : classifications.findByDataSourceIdAndSchemaName(classificationSourceId, schemaName);
        for (ClassificationEntity c : found) {
            if ("REJECTED".equalsIgnoreCase(c.getStatus()) || "IGNORED".equalsIgnoreCase(c.getStatus())) continue;
            Target t = new Target(firstText(c.getSchemaName(), schemaName), c.getTableName(), c.getColumnName());
            // A classification's PII type is more specific than one inferred from a function name.
            out.put(t, c.getPiiType());
        }
        return out;
    }

    /**
     * Map a masking function back onto a PII type so the pattern scan knows what to validate.
     * Only functions with a checksum- or format-verifiable identity are mapped; anything else
     * scans by source-hash comparison alone.
     */
    static String piiTypeForFunction(String function) {
        return switch (function == null ? "" : function.toUpperCase(Locale.ROOT)) {
            case "SSN" -> "SSN";
            case "NATIONAL_ID" -> "NATIONAL_ID";
            case "CREDIT_CARD" -> "CREDIT_CARD";
            case "IBAN" -> "IBAN";
            case "BANK_ACCOUNT" -> "BANK_ACCOUNT";
            case "EMAIL" -> "EMAIL";
            case "PHONE" -> "PHONE";
            case "ABA_ROUTING" -> "ROUTING";
            default -> "OTHER";
        };
    }

    /** Normalise a target's schema for this connection's identifier casing (Oracle folds upper). */
    private Target resolve(Connection c, Target t) {
        String schema = t.schema() == null || t.schema().isBlank()
                ? null : DataSourceService.normalizeSchema(c, t.schema());
        return new Target(schema, t.table(), t.column());
    }

    /**
     * Confirm the column really exists before querying it. A masking rule can outlive a dropped
     * column (schema drift), and a scan that blew up on the first stale rule would be useless.
     */
    private boolean columnExists(Connection c, Target t) {
        for (String table : new String[]{t.table(), t.table().toUpperCase(Locale.ROOT), t.table().toLowerCase(Locale.ROOT)}) {
            for (String column : new String[]{t.column(), t.column().toUpperCase(Locale.ROOT), t.column().toLowerCase(Locale.ROOT)}) {
                try (ResultSet rs = c.getMetaData().getColumns(null, t.schema(), table, column)) {
                    if (rs.next()) return true;
                } catch (SQLException ignored) {
                    // fall through and try the next casing
                }
            }
        }
        return false;
    }

    /** True when the column participates in the table's primary key or a unique index. */
    private boolean isUniqueKey(Connection c, Target t) {
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(null, t.schema(), t.table())) {
            int keyColumns = 0;
            boolean holdsColumn = false;
            while (rs.next()) {
                keyColumns++;
                if (t.column().equalsIgnoreCase(rs.getString("COLUMN_NAME"))) holdsColumn = true;
            }
            // Only a single-column key implies per-value uniqueness; a composite key does not.
            if (holdsColumn && keyColumns == 1) return true;
        } catch (SQLException ignored) { }
        try (ResultSet rs = c.getMetaData().getIndexInfo(null, t.schema(), t.table(), true, true)) {
            Map<String, Set<String>> byIndex = new LinkedHashMap<>();
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                byIndex.computeIfAbsent(name, k -> new LinkedHashSet<>()).add(column.toUpperCase(Locale.ROOT));
            }
            for (Set<String> cols : byIndex.values()) {
                if (cols.size() == 1 && cols.contains(t.column().toUpperCase(Locale.ROOT))) return true;
            }
        } catch (SQLException ignored) { }
        return false;
    }

    private static String selectColumn(Target t) {
        return "SELECT " + q(t.column()) + " FROM " + qualified(t);
    }

    private static String qualified(Target t) {
        return t.schema() == null || t.schema().isBlank() ? q(t.table()) : q(t.schema()) + "." + q(t.table());
    }

    /**
     * Quote an identifier, rejecting anything that is not a plain identifier. Scans build SQL from
     * stored metadata rather than user input, but a rule row is still data — so the same guard the
     * rest of the codebase uses applies here too.
     */
    static String q(String ident) {
        if (ident == null || !ident.matches("[A-Za-z0-9_$#]+")) {
            throw ApiException.bad("Illegal identifier in compliance scan: " + ident);
        }
        return "\"" + ident + "\"";
    }

    private static String key(String table, String column) {
        return (table == null ? "" : table.toLowerCase(Locale.ROOT)) + "."
                + (column == null ? "" : column.toLowerCase(Locale.ROOT));
    }

    private static String firstText(String preferred, String fallback) {
        return preferred != null && !preferred.isBlank() ? preferred : fallback;
    }

    private static String pct(double ratio) {
        return Math.round(ratio * 1000) / 10.0 + "%";
    }
}
