package io.forgetdm.compliance;

import io.forgetdm.common.ApiException;
import io.forgetdm.compliance.ComplianceScanner.Finding;
import io.forgetdm.compliance.ComplianceScanner.Outcome;
import io.forgetdm.compliance.ComplianceScanner.Target;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.discovery.ClassificationEntity;
import io.forgetdm.discovery.ClassificationRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Answers a data-subject erasure ("right to be forgotten") request for non-production data.
 *
 * <p>The question legal actually asks is "does this person exist anywhere in test?", and the honest
 * answer has two halves that most implementations conflate:
 *
 * <ol>
 *   <li><b>Is the raw identity present?</b> Search the identifier columns of every registered
 *       environment for the subject's value. A hit means masking did not cover this environment or
 *       this column, and the row must be erased or re-masked.</li>
 *   <li><b>Does a reversible mapping exist?</b> This is the half that decides the answer. If masking
 *       is deterministic and irreversible with no stored crosswalk, the real identity is not present
 *       in non-production <em>by construction</em> — nothing there can be traced back to the person,
 *       so there is nothing to erase. But if any crosswalk exists, <em>that crosswalk is itself
 *       personal data</em> and is squarely in scope for erasure.</li>
 * </ol>
 *
 * <p>ForgeTDM has exactly two structures that can be reversible, and both are checked:
 * <ul>
 *   <li>{@code masking_lookup_values} rows in {@code DIRECT} mode, which store
 *       {@code source_value -> replacement_value} and therefore <em>are</em> a crosswalk;</li>
 *   <li>{@code be_identity_links}, whose {@code external_id} / {@code key_values_json} retain real
 *       business keys to stitch an entity together across systems.</li>
 * </ul>
 * {@code HASH} mode lookups keep no source value and are one-way, so they are reported as safe.
 *
 * <p>The scanner is strictly read-only: it locates and reports, and never deletes. Erasure is a
 * governed action performed deliberately by an owner, not a side effect of a search.
 */
@Component
public class SubjectErasureScanner {

    /** Cap on columns probed per data source, so a wide estate cannot stall the request. */
    private static final int MAX_COLUMNS_PER_SOURCE = 400;

    /** PII types that can plausibly identify a subject and are therefore worth probing. */
    private static final Set<String> IDENTIFYING = Set.of(
            "SSN", "NATIONAL_ID", "TAX_ID", "EMAIL", "PHONE", "FAX", "CREDIT_CARD", "IBAN",
            "BANK_ACCOUNT", "PASSPORT", "DRIVER_LICENSE", "PERSON_ID", "USERNAME",
            "MEDICAL_RECORD_NUMBER", "HEALTH_PLAN_ID", "DEVICE_ID", "FULL_NAME", "VEHICLE_ID");

    private final ClassificationRepository classifications;
    private final MaskingRuleRepository rules;
    private final ConnectionFactory connections;
    private final ComplianceHasher hasher;
    private final JdbcTemplate jdbc;

    public SubjectErasureScanner(ClassificationRepository classifications, MaskingRuleRepository rules,
                                 ConnectionFactory connections, ComplianceHasher hasher, JdbcTemplate jdbc) {
        this.classifications = classifications;
        this.rules = rules;
        this.connections = connections;
        this.hasher = hasher;
        this.jdbc = jdbc;
    }

    /**
     * Search {@code targets} for {@code subjectValue} and report the erasure scope.
     *
     * @param subjectValue the subject's identifier as it exists in production (never persisted)
     * @param piiType      optional hint narrowing which columns to probe
     */
    public Outcome search(List<DataSourceEntity> targets, String subjectValue, String piiType) {
        if (subjectValue == null || subjectValue.isBlank()) {
            throw ApiException.bad("A subject identifier is required to run an erasure search.");
        }
        String subject = subjectValue.trim();
        List<Finding> findings = new ArrayList<>();
        int columnsProbed = 0;
        long rowsMatched = 0;

        // ---------------------------------------------------------- environments
        for (DataSourceEntity ds : targets) {
            Map<Target, String> candidates = candidateColumns(ds, piiType);
            if (candidates.isEmpty()) continue;
            try (Connection c = connections.open(ds)) {
                int probed = 0;
                for (Map.Entry<Target, String> entry : candidates.entrySet()) {
                    if (probed >= MAX_COLUMNS_PER_SOURCE) break;
                    Target t = resolve(c, entry.getKey());
                    if (!columnExists(c, t)) continue;
                    probed++;
                    columnsProbed++;
                    long hits = countMatches(c, t, subject);
                    if (hits > 0) {
                        rowsMatched += hits;
                        findings.add(new Finding("FAIL", "SUBJECT_PRESENT", t.schema(), t.table(), t.column(),
                                entry.getValue(), hits,
                                "The subject's raw identifier is present in " + ds.getName() + " at "
                                        + t.label() + " (" + hits + " row(s)) — this environment holds the real"
                                        + " identity, so it is in scope for erasure",
                                "Erase or re-mask these rows, then confirm why masking did not cover this column"
                                        + " (missing rule, unapproved prod copy, or a load that bypassed masking).",
                                hasher.witness(subject)));
                    }
                }
            } catch (ApiException e) {
                findings.add(new Finding("WARN", "SUBJECT_PRESENT", null, null, null, piiType, 0,
                        "Could not search " + ds.getName() + ": " + e.getMessage(),
                        "Fix connectivity for this environment and re-run, or record it as out of scope.", null));
            } catch (Exception e) {
                findings.add(new Finding("WARN", "SUBJECT_PRESENT", null, null, null, piiType, 0,
                        "Could not search " + ds.getName() + ": " + e.getMessage(),
                        "Fix connectivity for this environment and re-run, or record it as out of scope.", null));
            }
        }

        // ------------------------------------------------------------ crosswalks
        findings.addAll(crosswalkFindings(subject));

        // -------------------------------------------------------- reversibility
        findings.add(reversibilityVerdict(findings, subject));

        return new Outcome(findings, columnsProbed, rowsMatched);
    }

    // ------------------------------------------------------------- crosswalks

    /**
     * Look for a stored mapping that could turn a masked value back into this subject. A DIRECT-mode
     * lookup row or a retained business key is exactly the artefact an erasure request must reach.
     */
    private List<Finding> crosswalkFindings(String subject) {
        List<Finding> out = new ArrayList<>();

        // DIRECT lookups store source_value -> replacement_value: a reversible mapping.
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT lookup_name, COUNT(*) AS hits FROM masking_lookup_values "
                            + "WHERE lookup_mode = 'DIRECT' AND source_value = ? GROUP BY lookup_name", subject);
            for (Map<String, Object> row : rows) {
                long hits = ((Number) row.get("hits")).longValue();
                out.add(new Finding("FAIL", "CROSSWALK", null, "masking_lookup_values", "source_value",
                        "MAPPING", hits,
                        "A reversible DIRECT lookup in '" + row.get("lookup_name") + "' maps this subject's real"
                                + " value to its replacement (" + hits + " row(s)). This mapping is itself personal"
                                + " data and is in scope for erasure",
                        "Delete these lookup rows (or migrate the lookup to HASH mode, which retains no source"
                                + " value) so the masked data can no longer be traced back to the subject.",
                        hasher.witness(subject)));
            }
        } catch (RuntimeException e) {
            out.add(new Finding("WARN", "CROSSWALK", null, "masking_lookup_values", null, "MAPPING", 0,
                    "Could not inspect the masking lookup catalog: " + e.getMessage(),
                    "Verify the lookup catalog is reachable, then re-run the erasure search.", null));
        }

        // Business-entity identity links retain real external keys to stitch entities together.
        try {
            Long hits = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM be_identity_links WHERE external_id = ? OR key_values_json LIKE ?",
                    Long.class, subject, "%" + subject + "%");
            if (hits != null && hits > 0) {
                out.add(new Finding("FAIL", "CROSSWALK", null, "be_identity_links", "external_id",
                        "MAPPING", hits,
                        hits + " business-entity identity link(s) retain this subject's real business key,"
                                + " which allows the masked records to be re-identified",
                        "Erase these identity links (or replace the retained key with its masked equivalent)"
                                + " as part of the erasure action.",
                        hasher.witness(subject)));
            }
        } catch (RuntimeException e) {
            out.add(new Finding("WARN", "CROSSWALK", null, "be_identity_links", null, "MAPPING", 0,
                    "Could not inspect the identity crosswalk: " + e.getMessage(),
                    "Verify the identity registry is reachable, then re-run the erasure search.", null));
        }
        return out;
    }

    /**
     * The headline answer. If nothing holds the raw identity and no crosswalk exists, masking is
     * one-way for this subject and there is nothing in non-production to erase — which is the strong,
     * defensible position. Otherwise the finding states precisely what must be erased.
     */
    private Finding reversibilityVerdict(List<Finding> findings, String subject) {
        boolean present = findings.stream().anyMatch(f -> "SUBJECT_PRESENT".equals(f.check()) && "FAIL".equals(f.severity()));
        boolean crosswalk = findings.stream().anyMatch(f -> "CROSSWALK".equals(f.check()) && "FAIL".equals(f.severity()));
        long reversibleRules = rules.findAll().stream()
                .filter(r -> isReversibleFunction(r.getFunction()))
                .count();

        if (!present && !crosswalk) {
            return new Finding("INFO", "ERASURE_VERDICT", null, null, null, null, 0,
                    "No raw identifier and no reversible crosswalk was found for this subject. Masking is"
                            + " irreversible here, so the subject's identity does not exist in the scanned"
                            + " non-production environments and there is nothing to erase."
                            + (reversibleRules > 0
                                    ? " Note: " + reversibleRules + " reversible masking rule(s) (DIRECT lookups)"
                                      + " exist in this deployment — they hold no entry for this subject, but they"
                                      + " keep reversibility possible in general."
                                    : " No reversible masking rules are configured in this deployment."),
                    reversibleRules > 0
                            ? "Consider migrating DIRECT lookups to HASH mode so no crosswalk can accumulate."
                            : null,
                    hasher.witness(subject));
        }
        StringBuilder scope = new StringBuilder("This subject is reachable in non-production. Erasure scope: ");
        if (present) scope.append("raw identifier rows in the environments listed above");
        if (present && crosswalk) scope.append("; and ");
        if (crosswalk) scope.append("the reversible crosswalk entries listed above (these are personal data)");
        scope.append('.');
        return new Finding("FAIL", "ERASURE_VERDICT", null, null, null, null, 0, scope.toString(),
                "Erase every item listed, then re-run this search to evidence that the subject is no longer"
                        + " reachable. Record the before/after result in the erasure response.",
                hasher.witness(subject));
    }

    /** DIRECT lookups are reversible by design; every other supported function is one-way. */
    static boolean isReversibleFunction(String function) {
        return "DIRECT_LOOKUP".equalsIgnoreCase(function);
    }

    // ------------------------------------------------------------------ probing

    /**
     * Columns worth probing on a data source: identifier-ish PII classifications, plus any column a
     * masking rule targets with an identity function (a rule implies someone believed identity lived
     * there). Narrowed by {@code piiType} when the caller knows what they are searching for.
     */
    private Map<Target, String> candidateColumns(DataSourceEntity ds, String piiType) {
        Map<Target, String> out = new LinkedHashMap<>();
        String wanted = piiType == null || piiType.isBlank() ? null : piiType.trim().toUpperCase(Locale.ROOT);

        for (ClassificationEntity c : classifications.findByDataSourceId(ds.getId())) {
            if ("REJECTED".equalsIgnoreCase(c.getStatus()) || "IGNORED".equalsIgnoreCase(c.getStatus())) continue;
            String type = c.getPiiType() == null ? "" : c.getPiiType().toUpperCase(Locale.ROOT);
            if (wanted != null ? !wanted.equals(type) : !IDENTIFYING.contains(type)) continue;
            out.put(new Target(c.getSchemaName(), c.getTableName(), c.getColumnName()), c.getPiiType());
        }

        if (out.isEmpty()) {
            // No classifications for this source — fall back to rule-targeted identity columns so a
            // never-profiled environment is still searched rather than silently reported as clean.
            for (MaskingRuleEntity r : rules.findAll()) {
                String type = ComplianceScanner.piiTypeForFunction(r.getFunction());
                if ("OTHER".equals(type)) continue;
                if (wanted != null && !wanted.equals(type)) continue;
                out.put(new Target(r.getSchemaName(), r.getTableName(), r.getColumnName()), type);
            }
        }
        return out;
    }

    /**
     * Count rows where the column equals the subject value. Compared as text and bound as a
     * parameter — the value is never concatenated into SQL. Both the raw and the normalised form are
     * tried, because the same identity is stored with and without separators across systems.
     */
    private long countMatches(Connection c, Target t, String subject) {
        String normalized = ComplianceHasher.normalize(subject);
        Set<String> variants = new LinkedHashSet<>();
        variants.add(subject);
        if (normalized != null) variants.add(normalized);

        long total = 0;
        String sql = "SELECT COUNT(*) FROM " + qualified(t) + " WHERE " + ComplianceScanner.q(t.column()) + " = ?";
        for (String variant : variants) {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, variant);
                ps.setQueryTimeout(60);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) total += rs.getLong(1);
                }
            } catch (SQLException ignored) {
                // Type mismatch (comparing text to a numeric/date column) or a dropped column —
                // not a match, and not a reason to abort the whole subject search.
            }
        }
        return total;
    }

    private Target resolve(Connection c, Target t) {
        String schema = t.schema() == null || t.schema().isBlank()
                ? null : DataSourceService.normalizeSchema(c, t.schema());
        return new Target(schema, t.table(), t.column());
    }

    private boolean columnExists(Connection c, Target t) {
        for (String table : new String[]{t.table(), t.table().toUpperCase(Locale.ROOT), t.table().toLowerCase(Locale.ROOT)}) {
            for (String column : new String[]{t.column(), t.column().toUpperCase(Locale.ROOT), t.column().toLowerCase(Locale.ROOT)}) {
                try (ResultSet rs = c.getMetaData().getColumns(null, t.schema(), table, column)) {
                    if (rs.next()) return true;
                } catch (SQLException ignored) { }
            }
        }
        return false;
    }

    private static String qualified(Target t) {
        return t.schema() == null || t.schema().isBlank()
                ? ComplianceScanner.q(t.table())
                : ComplianceScanner.q(t.schema()) + "." + ComplianceScanner.q(t.table());
    }
}
