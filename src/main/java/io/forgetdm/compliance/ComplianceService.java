package io.forgetdm.compliance;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.compliance.ComplianceScanner.Finding;
import io.forgetdm.compliance.ComplianceScanner.Outcome;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.security.OwnershipGuard;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Orchestrates compliance assurance: runs a scan, persists its findings as durable evidence, and
 * records the whole thing in the tamper-evident audit ledger.
 *
 * <p>Every scan is stored rather than merely returned. That is the difference between a diagnostic
 * and evidence: an auditor's question is not "is it clean now?" but "show me that it was checked,
 * when, by whom, over what, and what you did about what you found."
 */
@Service
public class ComplianceService {

    private final ComplianceScanRepository scans;
    private final ComplianceFindingRepository findings;
    private final ComplianceScanner scanner;
    private final SubjectErasureScanner subjects;
    private final PiiExceptionService exceptions;
    private final MaskingPolicyRepository policies;
    private final DataSourceService dataSources;
    private final OwnershipGuard ownership;
    private final AuditService audit;
    private final ComplianceHasher hasher;

    public ComplianceService(ComplianceScanRepository scans, ComplianceFindingRepository findings,
                             ComplianceScanner scanner, SubjectErasureScanner subjects,
                             PiiExceptionService exceptions, MaskingPolicyRepository policies,
                             DataSourceService dataSources, OwnershipGuard ownership,
                             AuditService audit, ComplianceHasher hasher) {
        this.scans = scans;
        this.findings = findings;
        this.scanner = scanner;
        this.subjects = subjects;
        this.exceptions = exceptions;
        this.policies = policies;
        this.dataSources = dataSources;
        this.ownership = ownership;
        this.audit = audit;
        this.hasher = hasher;
    }

    // ==================================================================== scans

    /**
     * Run one or more checks and store the result.
     *
     * @param scanType COVERAGE | LEAK | CARDINALITY | FULL (all three)
     */
    public Map<String, Object> runScan(String scanType, Long targetId, Long sourceId, Long policyId,
                                       String schemaName, String environment, String name) {
        String type = scanType == null ? "FULL" : scanType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("COVERAGE", "LEAK", "CARDINALITY", "FULL").contains(type)) {
            throw ApiException.bad("Unknown scan type '" + type + "'. Use COVERAGE, LEAK, CARDINALITY or FULL.");
        }
        if (targetId == null) throw ApiException.bad("A target environment is required.");
        DataSourceEntity target = dataSources.get(targetId);
        DataSourceEntity source = sourceId == null ? null : dataSources.get(sourceId);
        if (policyId != null) requireVisiblePolicy(policyId);

        // A scan must never present a lapsed exception as still authorised.
        exceptions.expireOverdue();

        ComplianceScanEntity scan = begin(type, target, source, policyId, schemaName, environment, name, null);
        try {
            List<Finding> all = new ArrayList<>();
            int columns = 0;
            long rows = 0;

            if (type.equals("COVERAGE") || type.equals("FULL")) {
                // Coverage is judged against the source's classifications when we have one, because
                // that is where PII was discovered; otherwise fall back to the target's own profile.
                Outcome o = scanner.scanCoverage(source != null ? source : target, schemaName, policyId);
                all.addAll(o.findings());
                columns += o.columnsScanned();
            }
            if (type.equals("LEAK") || type.equals("FULL")) {
                Outcome o = scanner.scanLeaks(target, source, schemaName, policyId);
                all.addAll(o.findings());
                columns += o.columnsScanned();
                rows += o.rowsScanned();
            }
            if (type.equals("CARDINALITY") || type.equals("FULL")) {
                Outcome o = scanner.scanCardinality(target, schemaName, policyId);
                all.addAll(o.findings());
                rows += o.rowsScanned();
            }

            all.addAll(exceptionFindings(targetId));
            all = suppressAuthorised(all, targetId);

            return finish(scan, all, columns, rows);
        } catch (RuntimeException e) {
            return fail(scan, e);
        }
    }

    /**
     * Data-subject erasure search across every registered environment the caller can see (or a single
     * named one). The subject's value is used to query but is never stored — only its salted hash.
     */
    public Map<String, Object> runSubjectSearch(String subjectValue, String piiType, Long targetId) {
        if (subjectValue == null || subjectValue.isBlank()) {
            throw ApiException.bad("A subject identifier is required.");
        }
        List<DataSourceEntity> targets = targetId != null
                ? List.of(dataSources.get(targetId))
                : nonProductionSources();
        if (targets.isEmpty()) {
            throw ApiException.bad("No searchable environments are registered.");
        }

        DataSourceEntity primary = targets.get(0);
        ComplianceScanEntity scan = begin("SUBJECT", primary, null, null, null, null,
                "Erasure search across " + targets.size() + " environment(s)", hasher.hash(subjectValue));
        try {
            Outcome o = subjects.search(targets, subjectValue, piiType);
            return finish(scan, o.findings(), o.columnsScanned(), o.rowsScanned());
        } catch (RuntimeException e) {
            return fail(scan, e);
        }
    }

    // ------------------------------------------------------------ scan lifecycle

    private ComplianceScanEntity begin(String type, DataSourceEntity target, DataSourceEntity source,
                                       Long policyId, String schemaName, String environment, String name,
                                       String subjectHash) {
        ComplianceScanEntity s = new ComplianceScanEntity();
        s.setScanType(type);
        s.setName(name == null || name.isBlank()
                ? type.charAt(0) + type.substring(1).toLowerCase(Locale.ROOT) + " scan of " + target.getName()
                : name.trim());
        s.setEnvironment(environment == null || environment.isBlank() ? null : environment.trim().toUpperCase(Locale.ROOT));
        s.setTargetDataSourceId(target.getId());
        s.setSourceDataSourceId(source == null ? null : source.getId());
        s.setPolicyId(policyId);
        s.setSchemaName(schemaName == null || schemaName.isBlank() ? null : schemaName.trim());
        s.setSubjectValueHash(subjectHash);
        s.setStatus("RUNNING");
        s.setOwnerUserId(ownership.defaultOwnerUserId());
        s.setOwnerUsername(ownership.defaultOwnerUsername());
        s.setOwnerGroupId(ownership.defaultOwnerGroupId());
        s.setVisibility(ownership.defaultVisibility());
        return scans.save(s);
    }

    private Map<String, Object> finish(ComplianceScanEntity scan, List<Finding> found, int columns, long rows) {
        int fails = 0, warns = 0;
        List<ComplianceFindingEntity> rowsToSave = new ArrayList<>();
        for (Finding f : found) {
            if ("FAIL".equals(f.severity())) fails++;
            else if ("WARN".equals(f.severity())) warns++;
            rowsToSave.add(toEntity(scan.getId(), f));
        }
        findings.saveAll(rowsToSave);

        scan.setStatus("DONE");
        scan.setResult(fails > 0 ? "FAIL" : warns > 0 ? "WARN" : "PASS");
        scan.setColumnsScanned(columns);
        scan.setRowsScanned(rows);
        scan.setFailCount(fails);
        scan.setWarnCount(warns);
        scan.setSummary(summarise(scan, fails, warns, columns, rows));
        scan.setFinishedAt(Instant.now());
        ComplianceScanEntity saved = scans.save(scan);

        audit.record(actor(), "COMPLIANCE_SCAN_" + saved.getResult(), "SECURITY", "compliance-scan",
                String.valueOf(saved.getId()), saved.getName(), fails > 0 ? "FAILURE" : "SUCCESS",
                saved.getSummary(),
                "{\"scanType\":\"" + saved.getScanType() + "\",\"targetId\":" + saved.getTargetDataSourceId()
                        + ",\"fails\":" + fails + ",\"warns\":" + warns
                        + ",\"columns\":" + columns + ",\"rows\":" + rows + "}");
        return view(saved, true);
    }

    private Map<String, Object> fail(ComplianceScanEntity scan, RuntimeException e) {
        scan.setStatus("FAILED");
        scan.setResult("FAIL");
        scan.setError(truncate(e.getMessage(), 1900));
        scan.setFinishedAt(Instant.now());
        ComplianceScanEntity saved = scans.save(scan);
        audit.record(actor(), "COMPLIANCE_SCAN_FAILED", "SECURITY", "compliance-scan",
                String.valueOf(saved.getId()), saved.getName(), "FAILURE",
                "Scan failed: " + saved.getError(), null);
        throw e;
    }

    private static String summarise(ComplianceScanEntity s, int fails, int warns, int columns, long rows) {
        if ("SUBJECT".equals(s.getScanType())) {
            return fails > 0
                    ? "Subject is reachable in non-production — " + fails + " item(s) in erasure scope"
                    : "Subject is not reachable in non-production; nothing to erase";
        }
        String verdict = fails > 0 ? fails + " failure(s)" : warns > 0 ? warns + " warning(s)" : "no findings";
        return verdict + " across " + columns + " column(s)"
                + (rows > 0 ? " and " + rows + " row(s) scanned" : "");
    }

    // ---------------------------------------------------------- exception logic

    /** Turn expired / imminently-expiring exceptions into findings on this scan. */
    private List<Finding> exceptionFindings(Long targetId) {
        List<Finding> out = new ArrayList<>();
        for (PiiExceptionEntity e : exceptions.attentionNeeded()) {
            if (!e.getDataSourceId().equals(targetId)) continue;
            boolean expired = "EXPIRED".equals(e.getStatus()) || e.isExpired();
            out.add(new Finding(expired ? "FAIL" : "WARN", "EXCEPTION_EXPIRED", null, null, null,
                    e.getPiiType(), 0,
                    expired
                            ? "Exception #" + e.getId() + " for " + e.getScope() + " expired on "
                              + e.getExpiresAt() + " but the data it authorised may still be present"
                            : "Exception #" + e.getId() + " for " + e.getScope() + " expires on "
                              + e.getExpiresAt(),
                    expired
                            ? "Remove or re-mask the data this exception covered, or obtain a fresh approval."
                            : "Renew the approval or plan the removal before it lapses.",
                    null));
        }
        return out;
    }

    /**
     * Downgrade findings that fall inside an approved, unexpired exception from FAIL to WARN.
     *
     * <p>This is what makes the register meaningful. Without it, an approved exception still shows as
     * a red failure and the whole report gets ignored; with it, the report distinguishes "unmanaged
     * leak" from "known, justified, time-boxed risk" — but never hides it, which is why the finding
     * is retained as a warning that names the authorising exception.
     */
    private List<Finding> suppressAuthorised(List<Finding> found, Long targetId) {
        List<PiiExceptionEntity> active = exceptions.activeFor(targetId);
        if (active.isEmpty()) return found;

        List<Finding> out = new ArrayList<>(found.size());
        for (Finding f : found) {
            PiiExceptionEntity cover = !"FAIL".equals(f.severity()) ? null : covering(active, f);
            if (cover == null) {
                out.add(f);
                continue;
            }
            out.add(new Finding("WARN", f.check(), f.schema(), f.table(), f.column(), f.piiType(),
                    f.affectedRows(),
                    f.detail() + " — authorised by exception #" + cover.getId()
                            + " (approved by " + cover.getApprovedBy() + ", expires " + cover.getExpiresAt() + ")",
                    "No action required while the exception is valid; it lapses on " + cover.getExpiresAt() + ".",
                    f.evidenceHash()));
        }
        return out;
    }

    /** An exception covers a finding when its scope matches the column/table (or the whole schema). */
    private PiiExceptionEntity covering(List<PiiExceptionEntity> active, Finding f) {
        for (PiiExceptionEntity e : active) {
            if (e.getPiiType() != null && f.piiType() != null
                    && !e.getPiiType().equalsIgnoreCase(f.piiType())) continue;
            String scope = e.getScope().toLowerCase(Locale.ROOT).trim();
            String column = f.column() == null ? "" : f.column().toLowerCase(Locale.ROOT);
            String table = f.table() == null ? "" : f.table().toLowerCase(Locale.ROOT);
            String schema = f.schema() == null ? "" : f.schema().toLowerCase(Locale.ROOT);
            if (!column.isEmpty() && scope.endsWith(table + "." + column)) return e;
            if (!table.isEmpty() && (scope.equals(table) || scope.endsWith("." + table))) return e;
            if (!schema.isEmpty() && scope.equals(schema)) return e;
        }
        return null;
    }

    // ===================================================================== read

    public List<Map<String, Object>> listScans(String scanType, Long targetId, int limit) {
        int cap = limit < 1 ? 50 : Math.min(limit, 200);
        PageRequest page = PageRequest.of(0, cap);
        List<ComplianceScanEntity> rows;
        if (scanType != null && !scanType.isBlank()) {
            rows = scans.findByScanTypeOrderByStartedAtDesc(scanType.trim().toUpperCase(Locale.ROOT), page);
        } else if (targetId != null) {
            rows = scans.findByTargetDataSourceIdOrderByStartedAtDesc(targetId, page);
        } else {
            rows = scans.findAllByOrderByStartedAtDesc(page);
        }
        // Resolve every data-source name once. Previously each row looked its target and source up
        // individually, so a 25-row list issued 50 lookups purely to render labels.
        Map<Long, String> names = sourceNames();
        return rows.stream()
                .filter(s -> ownership.canSee(s.getOwnerUserId(), s.getOwnerGroupId(), s.getVisibility()))
                .map(s -> view(s, false, names))
                .toList();
    }

    private Map<Long, String> sourceNames() {
        Map<Long, String> names = new LinkedHashMap<>();
        try {
            for (DataSourceEntity d : dataSources.list()) names.put(d.getId(), d.getName());
        } catch (RuntimeException ignored) {
            // Fall back to per-row resolution if the list is unavailable.
        }
        return names;
    }

    public Map<String, Object> getScan(Long id) {
        return view(requireScan(id), true);
    }

    public void deleteScan(Long id) {
        ComplianceScanEntity s = requireScan(id);
        findings.deleteByScanId(id);
        scans.delete(s);
        audit.record(actor(), "COMPLIANCE_SCAN_DELETED", "SECURITY", "compliance-scan",
                String.valueOf(id), s.getName(), "SUCCESS", "Compliance scan evidence deleted", null);
    }

    /**
     * Posture summary for the dashboard: latest result per environment plus open exception counts.
     *
     * <p>Deliberately cheap. An earlier version verified the whole audit chain on every call, which
     * re-hashed every event in the ledger — 2.4s on a ledger of ~11k events, on a query the UI
     * refetches whenever the window regains focus. That made the page visibly flicker as the cards
     * unmounted and remounted around each refetch. Chain verification is now served from a short-TTL
     * cache, and the scan list is capped, so the posture call stays interactive as the ledger grows.
     */
    public Map<String, Object> posture() {
        exceptions.expireOverdue();
        List<Map<String, Object>> recent = listScans(null, null, 25);

        Map<String, Object> out = new LinkedHashMap<>();
        long fails = recent.stream().filter(m -> "FAIL".equals(m.get("result"))).count();
        long warns = recent.stream().filter(m -> "WARN".equals(m.get("result"))).count();
        long passes = recent.stream().filter(m -> "PASS".equals(m.get("result"))).count();
        out.put("scanCount", recent.size());
        out.put("failing", fails);
        out.put("warning", warns);
        out.put("passing", passes);
        out.put("lastScanAt", recent.isEmpty() ? null : recent.get(0).get("startedAt"));

        List<Map<String, Object>> allExceptions = exceptions.list();
        out.put("exceptionsTotal", allExceptions.size());
        out.put("exceptionsPending", allExceptions.stream().filter(m -> "PENDING".equals(m.get("status"))).count());
        out.put("exceptionsApproved", allExceptions.stream().filter(m -> "APPROVED".equals(m.get("status"))).count());
        out.put("exceptionsExpired", allExceptions.stream().filter(m -> Boolean.TRUE.equals(m.get("expired"))).count());
        out.put("auditChain", cachedChainValid());
        out.put("recent", recent.stream().limit(10).toList());
        return out;
    }

    // Chain verification is O(ledger) with a SHA-256 per row, so it is cached briefly rather than
    // recomputed per request. The audit page still calls /api/audit/verify for an authoritative,
    // uncached answer; this value only drives a status chip.
    private static final long CHAIN_CACHE_MS = 60_000;
    private volatile long chainCheckedAt;
    private volatile Boolean chainValid;

    private Boolean cachedChainValid() {
        long now = System.currentTimeMillis();
        Boolean cached = chainValid;
        if (cached != null && now - chainCheckedAt < CHAIN_CACHE_MS) return cached;
        try {
            Boolean fresh = Boolean.TRUE.equals(audit.verifyChain().get("valid"));
            chainValid = fresh;
            chainCheckedAt = now;
            return fresh;
        } catch (RuntimeException e) {
            // A verification problem must not take the posture endpoint down with it.
            return cached;
        }
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Every registered data source that is not flagged as a production source. Erasure and leak
     * scanning target non-production by definition; scanning production would be both pointless and
     * a fresh exposure of the data we are trying to protect.
     */
    List<DataSourceEntity> nonProductionSources() {
        return dataSources.list().stream()
                .filter(d -> {
                    String role = d.getRole() == null ? "" : d.getRole().toUpperCase(Locale.ROOT);
                    String name = d.getName() == null ? "" : d.getName().toUpperCase(Locale.ROOT);
                    boolean looksProd = name.contains("PROD") && !name.contains("NON-PROD") && !name.contains("NONPROD");
                    return !looksProd && !"SOURCE_PROD".equals(role);
                })
                .toList();
    }

    ComplianceScanEntity requireScan(Long id) {
        ComplianceScanEntity s = scans.findById(id)
                .orElseThrow(() -> ApiException.notFound("Compliance scan " + id + " not found"));
        ownership.assertCanSee("compliance scan", id, s.getOwnerUserId(), s.getOwnerGroupId(), s.getVisibility());
        return s;
    }

    private MaskingPolicyEntity requireVisiblePolicy(Long id) {
        MaskingPolicyEntity p = policies.findById(id)
                .orElseThrow(() -> ApiException.notFound("Policy " + id + " not found"));
        ownership.assertCanSee("policy", id, p.getOwnerUserId(), p.getOwnerGroupId(), p.getVisibility());
        return p;
    }

    private static ComplianceFindingEntity toEntity(Long scanId, Finding f) {
        ComplianceFindingEntity e = new ComplianceFindingEntity();
        e.setScanId(scanId);
        e.setSeverity(f.severity());
        e.setCheckName(f.check());
        e.setSchemaName(f.schema());
        e.setTableName(f.table());
        e.setColumnName(f.column());
        e.setPiiType(f.piiType());
        e.setAffectedRows(f.affectedRows());
        e.setDetail(truncate(f.detail(), 1990));
        e.setRemediation(truncate(f.remediation(), 990));
        e.setEvidenceHash(f.evidenceHash());
        return e;
    }

    Map<String, Object> view(ComplianceScanEntity s, boolean withFindings) {
        return view(s, withFindings, null);
    }

    Map<String, Object> view(ComplianceScanEntity s, boolean withFindings, Map<Long, String> names) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("scanType", s.getScanType());
        m.put("name", s.getName());
        m.put("environment", s.getEnvironment());
        m.put("targetDataSourceId", s.getTargetDataSourceId());
        m.put("targetName", resolveName(s.getTargetDataSourceId(), names));
        m.put("sourceDataSourceId", s.getSourceDataSourceId());
        m.put("sourceName", resolveName(s.getSourceDataSourceId(), names));
        m.put("policyId", s.getPolicyId());
        m.put("schemaName", s.getSchemaName());
        m.put("subjectValueHash", s.getSubjectValueHash());
        m.put("status", s.getStatus());
        m.put("result", s.getResult());
        m.put("columnsScanned", s.getColumnsScanned());
        m.put("rowsScanned", s.getRowsScanned());
        m.put("failCount", s.getFailCount());
        m.put("warnCount", s.getWarnCount());
        m.put("summary", s.getSummary());
        m.put("error", s.getError());
        m.put("startedAt", s.getStartedAt());
        m.put("finishedAt", s.getFinishedAt());
        m.put("owner", s.getOwnerUsername());
        if (withFindings) {
            m.put("findings", findings.findByScanIdOrderBySeverityAscIdAsc(s.getId()).stream()
                    .map(ComplianceService::findingView).toList());
        }
        return m;
    }

    static Map<String, Object> findingView(ComplianceFindingEntity f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", f.getId());
        m.put("severity", f.getSeverity());
        m.put("check", f.getCheckName());
        m.put("schema", f.getSchemaName());
        m.put("table", f.getTableName());
        m.put("column", f.getColumnName());
        m.put("piiType", f.getPiiType());
        m.put("affectedRows", f.getAffectedRows());
        m.put("detail", f.getDetail());
        m.put("remediation", f.getRemediation());
        m.put("evidenceHash", f.getEvidenceHash());
        return m;
    }

    List<ComplianceFindingEntity> findingsFor(Long scanId) {
        return findings.findByScanIdOrderBySeverityAscIdAsc(scanId);
    }

    private String resolveName(Long id, Map<Long, String> names) {
        if (id == null) return null;
        if (names != null) {
            String cached = names.get(id);
            if (cached != null) return cached;
        }
        try { return dataSources.get(id).getName(); }
        catch (RuntimeException e) { return "(data source " + id + ")"; }
    }

    private String actor() {
        return ownership.caller().map(p -> p.username()).orElse("system");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
