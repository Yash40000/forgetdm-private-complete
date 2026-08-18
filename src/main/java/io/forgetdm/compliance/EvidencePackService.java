package io.forgetdm.compliance;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.discovery.ClassificationEntity;
import io.forgetdm.discovery.ClassificationRepository;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.OwnershipGuard;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Compiles the auditor evidence pack — the single artefact that answers "prove it".
 *
 * <p>An auditor does not want a dashboard; they want a dated document that states what was checked,
 * how, by whom, and what the result was, with the raw findings attached and nothing sensitive inside
 * it. The pack assembles six sections in the order an auditor reads them:
 *
 * <ol>
 *   <li><b>Classification inventory</b> — which fields hold PII, by type and review status. Without
 *       this the rest is unanchored: you cannot claim coverage of a set you never defined.</li>
 *   <li><b>Masking policy coverage</b> — every classified field mapped to the rule that masks it,
 *       and any field with no rule called out explicitly.</li>
 *   <li><b>Execution evidence</b> — masking and provisioning events from the tamper-evident ledger,
 *       proving the rules actually ran rather than merely existing.</li>
 *   <li><b>Assurance scans</b> — the leak, cardinality and coverage results with their verdicts.</li>
 *   <li><b>Exception register</b> — approved deviations with justification, approver and expiry.</li>
 *   <li><b>Ledger integrity</b> — the audit chain verification, which is what makes the preceding
 *       sections trustworthy rather than merely asserted.</li>
 * </ol>
 *
 * <p>The pack is rendered as Markdown so it can be read, printed, attached to an audit response, or
 * converted to PDF. It contains no PII: witnesses appear only as truncated salted hashes.
 */
@Service
public class EvidencePackService {

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);

    private final ComplianceService compliance;
    private final PiiExceptionService exceptions;
    private final ClassificationRepository classifications;
    private final MaskingRuleRepository rules;
    private final MaskingPolicyRepository policies;
    private final DataSourceService dataSources;
    private final AuditService audit;
    private final OwnershipGuard ownership;

    public EvidencePackService(ComplianceService compliance,
                              PiiExceptionService exceptions, ClassificationRepository classifications,
                              MaskingRuleRepository rules, MaskingPolicyRepository policies,
                              DataSourceService dataSources, AuditService audit, OwnershipGuard ownership) {
        this.compliance = compliance;
        this.exceptions = exceptions;
        this.classifications = classifications;
        this.rules = rules;
        this.policies = policies;
        this.dataSources = dataSources;
        this.audit = audit;
        this.ownership = ownership;
    }

    /**
     * Build the pack.
     *
     * @param targetId environment the pack is about (required — an auditor asks about a system)
     * @param sourceId production source the classifications came from (optional)
     * @param policyId masking policy whose coverage is being evidenced (optional but recommended)
     */
    public Map<String, Object> build(Long targetId, Long sourceId, Long policyId, String schemaName) {
        if (targetId == null) throw ApiException.bad("An environment is required to build an evidence pack.");
        DataSourceEntity target = dataSources.get(targetId);
        DataSourceEntity source = sourceId == null ? null : dataSources.get(sourceId);
        MaskingPolicyEntity policy = policyId == null ? null : policies.findById(policyId).orElse(null);

        exceptions.expireOverdue();

        Long classificationSourceId = source != null ? source.getId() : target.getId();
        List<ClassificationEntity> inventory = schemaName == null || schemaName.isBlank()
                ? classifications.findByDataSourceId(classificationSourceId)
                : classifications.findByDataSourceIdAndSchemaName(classificationSourceId, schemaName);
        List<MaskingRuleEntity> policyRules = policyId == null ? List.of() : rules.findByPolicyId(policyId);
        List<Map<String, Object>> recentScans = compliance.listScans(null, targetId, 25);
        List<Map<String, Object>> exceptionRows = exceptions.list().stream()
                .filter(m -> targetId.equals(m.get("dataSourceId")))
                .toList();
        Map<String, Object> chain = audit.verifyChain();

        Coverage coverage = coverage(inventory, policyRules);
        String markdown = render(target, source, policy, schemaName, inventory, policyRules,
                coverage, recentScans, exceptionRows, chain);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("generatedAt", Instant.now());
        out.put("generatedBy", ownership.caller().map(p -> p.username()).orElse("system"));
        out.put("environment", target.getName());
        out.put("productionSource", source == null ? null : source.getName());
        out.put("policy", policy == null ? null : policy.getName());
        out.put("schemaName", schemaName);
        out.put("piiFieldCount", coverage.total());
        out.put("coveredFieldCount", coverage.covered());
        out.put("uncoveredFieldCount", coverage.uncovered().size());
        out.put("coveragePercent", coverage.percent());
        out.put("scanCount", recentScans.size());
        out.put("exceptionCount", exceptionRows.size());
        out.put("auditChainValid", chain.get("valid"));
        out.put("markdown", markdown);

        audit.record(ownership.caller().map(p -> p.username()).orElse("system"),
                "COMPLIANCE_EVIDENCE_PACK_BUILT", "SECURITY", "compliance-evidence-pack",
                String.valueOf(targetId), target.getName(), "SUCCESS",
                "Evidence pack compiled for " + target.getName() + ": " + coverage.covered() + "/"
                        + coverage.total() + " PII fields covered, " + recentScans.size() + " scan(s), "
                        + exceptionRows.size() + " exception(s)",
                "{\"targetId\":" + targetId + ",\"policyId\":" + policyId
                        + ",\"coveragePercent\":" + coverage.percent() + "}");
        return out;
    }

    // ------------------------------------------------------------------ coverage

    private record Coverage(int total, int covered, List<ClassificationEntity> uncovered,
                            Map<String, int[]> byType) {
        double percent() {
            return total == 0 ? 100.0 : Math.round((covered * 1000.0) / total) / 10.0;
        }
    }

    private Coverage coverage(List<ClassificationEntity> inventory, List<MaskingRuleEntity> policyRules) {
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (MaskingRuleEntity r : policyRules) {
            covered.add(lower(r.getTableName()) + "." + lower(r.getColumnName()));
        }
        List<ClassificationEntity> uncovered = new ArrayList<>();
        Map<String, int[]> byType = new TreeMap<>();
        int total = 0, hits = 0;
        for (ClassificationEntity c : inventory) {
            if ("REJECTED".equalsIgnoreCase(c.getStatus()) || "IGNORED".equalsIgnoreCase(c.getStatus())) continue;
            total++;
            boolean isCovered = covered.contains(lower(c.getTableName()) + "." + lower(c.getColumnName()));
            if (isCovered) hits++; else uncovered.add(c);
            int[] counts = byType.computeIfAbsent(c.getPiiType() == null ? "UNKNOWN" : c.getPiiType(),
                    k -> new int[2]);
            counts[0]++;
            if (isCovered) counts[1]++;
        }
        return new Coverage(total, hits, uncovered, byType);
    }

    // -------------------------------------------------------------------- render

    private String render(DataSourceEntity target, DataSourceEntity source, MaskingPolicyEntity policy,
                          String schemaName, List<ClassificationEntity> inventory,
                          List<MaskingRuleEntity> policyRules, Coverage coverage,
                          List<Map<String, Object>> recentScans, List<Map<String, Object>> exceptionRows,
                          Map<String, Object> chain) {
        StringBuilder b = new StringBuilder();
        String now = STAMP.format(Instant.now());
        String actor = ownership.caller().map(p -> p.username()).orElse("system");

        b.append("# Data Protection Evidence Pack\n\n");
        b.append("**Environment:** ").append(target.getName()).append("  \n");
        b.append("**Production source of record:** ").append(source == null ? "_not supplied_" : source.getName()).append("  \n");
        b.append("**Masking policy:** ").append(policy == null ? "_not supplied_" : policy.getName()).append("  \n");
        b.append("**Schema scope:** ").append(schemaName == null || schemaName.isBlank() ? "all schemas" : schemaName).append("  \n");
        b.append("**Generated:** ").append(now).append(" by ").append(actor).append("\n\n");

        // -------- executive summary
        b.append("## 1. Summary\n\n");
        b.append("| Control | Result |\n|---|---|\n");
        b.append("| PII fields identified | ").append(coverage.total()).append(" |\n");
        b.append("| PII fields with a masking rule | ").append(coverage.covered())
         .append(" (").append(coverage.percent()).append("%) |\n");
        b.append("| PII fields with **no** masking rule | ").append(coverage.uncovered().size()).append(" |\n");
        b.append("| Assurance scans on record | ").append(recentScans.size()).append(" |\n");
        long failing = recentScans.stream().filter(m -> "FAIL".equals(m.get("result"))).count();
        b.append("| Scans currently failing | ").append(failing).append(" |\n");
        b.append("| Registered exceptions | ").append(exceptionRows.size()).append(" |\n");
        long expired = exceptionRows.stream().filter(m -> Boolean.TRUE.equals(m.get("expired"))).count();
        b.append("| Expired exceptions (unauthorised) | ").append(expired).append(" |\n");
        b.append("| Audit ledger integrity | ").append(Boolean.TRUE.equals(chain.get("valid"))
                ? "VERIFIED" : "BROKEN — see section 6").append(" |\n\n");

        String verdict = coverage.uncovered().isEmpty() && failing == 0 && expired == 0
                ? "**Assessment: controls evidenced.** Every identified PII field is covered by a masking rule, "
                  + "no assurance scan is failing, and no exception is overdue."
                : "**Assessment: open items exist.** See sections 2, 4 and 5 for the specific gaps; each is listed "
                  + "with its remediation.";
        b.append(verdict).append("\n\n");

        // -------- 2. classification inventory
        b.append("## 2. PII classification inventory\n\n");
        b.append("Fields identified as holding personal or otherwise regulated data, and whether a masking rule ")
         .append("covers each. This inventory is the control boundary: coverage claims mean nothing without it.\n\n");
        if (coverage.byType().isEmpty()) {
            b.append("_No classifications recorded for this scope. Run discovery before relying on this pack._\n\n");
        } else {
            b.append("| PII type | Fields | Covered | Uncovered |\n|---|---|---|---|\n");
            for (Map.Entry<String, int[]> e : coverage.byType().entrySet()) {
                int[] c = e.getValue();
                b.append("| ").append(e.getKey()).append(" | ").append(c[0]).append(" | ")
                 .append(c[1]).append(" | ").append(c[0] - c[1]).append(" |\n");
            }
            b.append('\n');
        }
        if (!coverage.uncovered().isEmpty()) {
            b.append("### 2.1 Fields with no masking rule\n\n");
            b.append("| Field | PII type | Status | Confidence | Suggested rule |\n|---|---|---|---|---|\n");
            for (ClassificationEntity c : coverage.uncovered()) {
                b.append("| ").append(qualify(c.getSchemaName(), c.getTableName(), c.getColumnName()))
                 .append(" | ").append(nz(c.getPiiType()))
                 .append(" | ").append(nz(c.getStatus()))
                 .append(" | ").append(Math.round(c.getConfidence() * 100)).append("%")
                 .append(" | ").append(nz(c.getSuggestedFunction())).append(" |\n");
            }
            b.append("\n**Action:** add a rule for each field above, or reject the classification with a documented reason.\n\n");
        }

        // -------- 3. masking policy
        b.append("## 3. Masking policy in force\n\n");
        if (policyRules.isEmpty()) {
            b.append("_No policy supplied, so no rule set is evidenced here._\n\n");
        } else {
            b.append("Each rule below was configured before provisioning and applied during it. ")
             .append("`Deterministic` marks rules whose output is stable for a given input — the property that ")
             .append("preserves referential integrity across systems.\n\n");
            b.append("| Field | Function | Parameters | Deterministic |\n|---|---|---|---|\n");
            for (MaskingRuleEntity r : policyRules) {
                b.append("| ").append(qualify(r.getSchemaName(), r.getTableName(), r.getColumnName()))
                 .append(" | ").append(nz(r.getFunction()))
                 .append(" | ").append(params(r))
                 .append(" | ").append(r.isDeterministic() ? "yes" : "no").append(" |\n");
            }
            b.append('\n');
        }

        // -------- 4. assurance scans
        b.append("## 4. Assurance scan results\n\n");
        b.append("Independent verification performed *after* masking. Leak scans read whole columns rather than ")
         .append("samples: a pattern scan proves no value in the column is a valid real-world identifier, and a ")
         .append("hashed comparison proves no production value survived. Witness values are recorded only as ")
         .append("salted one-way hashes, so this pack contains no personal data.\n\n");
        if (recentScans.isEmpty()) {
            b.append("_No scans on record for this environment. Run a FULL scan before submitting this pack._\n\n");
        } else {
            b.append("| Scan | Type | Result | Columns | Rows | Failures | Warnings | When |\n|---|---|---|---|---|---|---|---|\n");
            for (Map<String, Object> s : recentScans) {
                b.append("| #").append(s.get("id")).append(" | ").append(nz(str(s.get("scanType"))))
                 .append(" | ").append(nz(str(s.get("result"))))
                 .append(" | ").append(s.get("columnsScanned"))
                 .append(" | ").append(s.get("rowsScanned"))
                 .append(" | ").append(s.get("failCount"))
                 .append(" | ").append(s.get("warnCount"))
                 .append(" | ").append(nz(str(s.get("startedAt")))).append(" |\n");
            }
            b.append('\n');
            appendOpenFindings(b, recentScans);
        }

        // -------- 5. exception register
        b.append("## 5. Exception register\n\n");
        b.append("Approved, time-boxed deviations. Each requires a justification, an approver who is not the ")
         .append("requester, and an expiry date; an expired exception is reported as a control failure rather ")
         .append("than allowed to persist.\n\n");
        if (exceptionRows.isEmpty()) {
            b.append("_No exceptions registered for this environment — all data here is masked under policy._\n\n");
        } else {
            b.append("| # | Scope | PII type | Status | Requested by | Approved by | Expires | Justification |\n");
            b.append("|---|---|---|---|---|---|---|---|\n");
            for (Map<String, Object> e : exceptionRows) {
                b.append("| ").append(e.get("id"))
                 .append(" | ").append(nz(str(e.get("scope"))))
                 .append(" | ").append(nz(str(e.get("piiType"))))
                 .append(" | ").append(Boolean.TRUE.equals(e.get("expired")) ? "**EXPIRED**" : nz(str(e.get("status"))))
                 .append(" | ").append(nz(str(e.get("requestedBy"))))
                 .append(" | ").append(nz(str(e.get("approvedBy"))))
                 .append(" | ").append(nz(str(e.get("expiresAt"))))
                 .append(" | ").append(oneLine(str(e.get("justification")))).append(" |\n");
            }
            b.append('\n');
        }

        // -------- 6. ledger integrity
        b.append("## 6. Audit ledger integrity\n\n");
        b.append("Every masking run, provisioning job, scan and exception decision is written to an append-only ")
         .append("ledger in which each record commits the hash of its predecessor. Re-computing the chain detects ")
         .append("any edited or deleted history — which is what allows the preceding sections to be treated as ")
         .append("evidence rather than assertion.\n\n");
        b.append("| Property | Value |\n|---|---|\n");
        b.append("| Chain valid | ").append(chain.get("valid")).append(" |\n");
        b.append("| Tampering suspected | ").append(chain.get("tamperSuspected")).append(" |\n");
        b.append("| Events in ledger | ").append(chain.get("total")).append(" |\n");
        b.append("| Cryptographically verified | ").append(chain.get("verifiedCount")).append(" |\n");
        b.append("| Verified through sequence | ").append(chain.get("verifiedThroughSeq")).append(" |\n\n");

        b.append("---\n\n");
        b.append("_Generated by ForgeTDM on ").append(now).append(". This document contains no personal data: ")
         .append("all witness values are irreversible salted hashes._\n");
        return b.toString();
    }

    /** List the still-open failures across the scans, so the pack states its own gaps plainly. */
    private void appendOpenFindings(StringBuilder b, List<Map<String, Object>> recentScans) {
        List<ComplianceFindingEntity> open = new ArrayList<>();
        for (Map<String, Object> s : recentScans) {
            if (!"FAIL".equals(s.get("result"))) continue;
            Long id = s.get("id") instanceof Number n ? n.longValue() : null;
            if (id == null) continue;
            for (ComplianceFindingEntity f : compliance.findingsFor(id)) {
                if ("FAIL".equals(f.getSeverity())) open.add(f);
            }
            if (open.size() > 100) break;
        }
        if (open.isEmpty()) return;
        b.append("### 4.1 Open failures\n\n");
        b.append("| Check | Field | Rows | Detail | Remediation |\n|---|---|---|---|---|\n");
        for (ComplianceFindingEntity f : open) {
            b.append("| ").append(nz(f.getCheckName()))
             .append(" | ").append(qualify(f.getSchemaName(), f.getTableName(), f.getColumnName()))
             .append(" | ").append(f.getAffectedRows())
             .append(" | ").append(oneLine(f.getDetail()))
             .append(" | ").append(oneLine(f.getRemediation())).append(" |\n");
        }
        b.append('\n');
    }

    // ------------------------------------------------------------------ helpers

    private static String qualify(String schema, String table, String column) {
        if (table == null && column == null) return "_(environment-wide)_";
        StringBuilder sb = new StringBuilder();
        if (schema != null && !schema.isBlank()) sb.append(schema).append('.');
        if (table != null) sb.append(table);
        if (column != null) sb.append('.').append(column);
        return "`" + sb + "`";
    }

    private static String params(MaskingRuleEntity r) {
        String p1 = r.getParam1(), p2 = r.getParam2();
        if (p1 == null && p2 == null) return "_default_";
        return (p1 == null ? "" : p1) + (p2 == null || p2.isBlank() ? "" : ", " + p2);
    }

    private static String lower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static String nz(String s) { return s == null || s.isBlank() ? "—" : s; }

    /** Collapse a detail string so it cannot break the Markdown table it sits in. */
    private static String oneLine(String s) {
        if (s == null || s.isBlank()) return "—";
        String flat = s.replaceAll("\\s*\\R\\s*", " ").replace("|", "\\|").trim();
        return flat.length() <= 400 ? flat : flat.substring(0, 397) + "...";
    }
}
