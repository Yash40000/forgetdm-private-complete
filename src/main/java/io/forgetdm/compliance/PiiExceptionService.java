package io.forgetdm.compliance;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.OwnershipGuard;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The exception register: approved, time-boxed permission for unmasked production data to exist in a
 * non-production environment.
 *
 * <p>Exceptions are a fact of life — a defect that only reproduces on real data, a vendor file that
 * cannot be synthesised. Pretending otherwise is what produces undocumented prod copies. What makes
 * an exception defensible rather than a finding is that it is written down, justified, approved by
 * someone other than the requester (four-eyes), given compensating controls, and <b>given an expiry</b>.
 *
 * <p>Expiry is the load-bearing part: {@link #expireOverdue()} flips lapsed exceptions to EXPIRED and
 * {@link ComplianceService} reports them as failures, so a "temporary" prod copy cannot quietly
 * become permanent — the usual way this control fails in practice.
 */
@Service
public class PiiExceptionService {

    /** Longest exception anyone may request in one go; renewal is a deliberate re-approval. */
    private static final int MAX_DAYS = 180;

    private final PiiExceptionRepository repo;
    private final DataSourceService dataSources;
    private final OwnershipGuard ownership;
    private final AuditService audit;

    public PiiExceptionService(PiiExceptionRepository repo, DataSourceService dataSources,
                              OwnershipGuard ownership, AuditService audit) {
        this.repo = repo;
        this.dataSources = dataSources;
        this.ownership = ownership;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ create

    public Map<String, Object> request(Long dataSourceId, String environment, String scope, String piiType,
                                       String justification, String compensatingControls, Integer days) {
        if (dataSourceId == null) throw ApiException.bad("A data source is required for an exception.");
        dataSources.get(dataSourceId);                       // 404s if it does not exist / is not visible
        if (blank(scope)) throw ApiException.bad("Describe the scope of the exception (schema, table or column).");
        if (blank(justification) || justification.trim().length() < 20) {
            throw ApiException.bad("A justification of at least 20 characters is required — an auditor will read it.");
        }
        int validDays = days == null || days < 1 ? 30 : Math.min(days, MAX_DAYS);

        PiiExceptionEntity e = new PiiExceptionEntity();
        e.setDataSourceId(dataSourceId);
        e.setEnvironment(blank(environment) ? "NON-PROD" : environment.trim().toUpperCase(Locale.ROOT));
        e.setScope(scope.trim());
        e.setPiiType(blank(piiType) ? null : piiType.trim().toUpperCase(Locale.ROOT));
        e.setJustification(justification.trim());
        e.setCompensatingControls(blank(compensatingControls) ? null : compensatingControls.trim());
        e.setRequestedBy(actor());
        e.setExpiresAt(Instant.now().plus(validDays, ChronoUnit.DAYS));
        e.setStatus("PENDING");
        e.setOwnerUserId(ownership.defaultOwnerUserId());
        e.setOwnerUsername(ownership.defaultOwnerUsername());
        e.setOwnerGroupId(ownership.defaultOwnerGroupId());
        e.setVisibility(ownership.defaultVisibility());
        PiiExceptionEntity saved = repo.save(e);

        audit.record(actor(), "PII_EXCEPTION_REQUESTED", "SECURITY", "pii-exception",
                String.valueOf(saved.getId()), saved.getScope(), "SUCCESS",
                "Exception requested for " + saved.getScope() + " in " + saved.getEnvironment()
                        + ", expires " + saved.getExpiresAt(),
                "{\"dataSourceId\":" + dataSourceId + ",\"days\":" + validDays + "}");
        return view(saved);
    }

    // ------------------------------------------------------------------ decide

    /**
     * Approve an exception. The approver may not be the requester — an exception someone grants
     * themselves is not a control, and this is the first thing an auditor tests.
     */
    public Map<String, Object> approve(Long id, String note) {
        PiiExceptionEntity e = require(id);
        if (!"PENDING".equals(e.getStatus())) {
            throw ApiException.bad("Only a pending exception can be approved (this one is " + e.getStatus() + ").");
        }
        String approver = actor();
        if (approver.equalsIgnoreCase(e.getRequestedBy())) {
            throw ApiException.bad("Segregation of duties: the requester cannot approve their own exception. "
                    + "A different authorised approver must review it.");
        }
        e.setStatus("APPROVED");
        e.setApprovedBy(approver);
        e.setApprovedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        if (!blank(note)) {
            e.setCompensatingControls(blank(e.getCompensatingControls())
                    ? note.trim() : e.getCompensatingControls() + " | approver note: " + note.trim());
        }
        PiiExceptionEntity saved = repo.save(e);
        audit.record(approver, "PII_EXCEPTION_APPROVED", "SECURITY", "pii-exception",
                String.valueOf(id), saved.getScope(), "SUCCESS",
                "Exception approved for " + saved.getScope() + " until " + saved.getExpiresAt(), null);
        return view(saved);
    }

    public Map<String, Object> reject(Long id, String reason) {
        PiiExceptionEntity e = require(id);
        if (!"PENDING".equals(e.getStatus())) {
            throw ApiException.bad("Only a pending exception can be rejected (this one is " + e.getStatus() + ").");
        }
        if (blank(reason)) throw ApiException.bad("A rejection reason is required.");
        e.setStatus("REJECTED");
        e.setApprovedBy(actor());
        e.setApprovedAt(Instant.now());
        e.setUpdatedAt(Instant.now());
        e.setCompensatingControls("Rejected: " + reason.trim());
        PiiExceptionEntity saved = repo.save(e);
        audit.record(actor(), "PII_EXCEPTION_REJECTED", "SECURITY", "pii-exception",
                String.valueOf(id), saved.getScope(), "SUCCESS", "Exception rejected: " + reason.trim(), null);
        return view(saved);
    }

    /** Revoke an already-approved exception early (the risk changed, or the need went away). */
    public Map<String, Object> revoke(Long id, String reason) {
        PiiExceptionEntity e = require(id);
        if (!"APPROVED".equals(e.getStatus())) {
            throw ApiException.bad("Only an approved exception can be revoked (this one is " + e.getStatus() + ").");
        }
        e.setStatus("REVOKED");
        e.setUpdatedAt(Instant.now());
        PiiExceptionEntity saved = repo.save(e);
        audit.record(actor(), "PII_EXCEPTION_REVOKED", "SECURITY", "pii-exception",
                String.valueOf(id), saved.getScope(), "SUCCESS",
                "Exception revoked" + (blank(reason) ? "" : ": " + reason.trim()), null);
        return view(saved);
    }

    // ------------------------------------------------------------------- expiry

    /**
     * Flip lapsed exceptions to EXPIRED. Called before every compliance scan and evidence pack so a
     * report can never present an overdue exception as still valid.
     *
     * @return the number of exceptions that lapsed on this pass
     */
    public int expireOverdue() {
        int expired = 0;
        for (PiiExceptionEntity e : repo.findByStatus("APPROVED")) {
            if (e.getExpiresAt() != null && e.getExpiresAt().isBefore(Instant.now())) {
                e.setStatus("EXPIRED");
                e.setUpdatedAt(Instant.now());
                repo.save(e);
                expired++;
                audit.record("system", "PII_EXCEPTION_EXPIRED", "SECURITY", "pii-exception",
                        String.valueOf(e.getId()), e.getScope(), "FAILURE",
                        "Exception for " + e.getScope() + " in " + e.getEnvironment()
                                + " lapsed on " + e.getExpiresAt() + " and is no longer authorised", null);
            }
        }
        return expired;
    }

    /** Exceptions that should appear as findings: expired, or approved with an imminent expiry. */
    public List<PiiExceptionEntity> attentionNeeded() {
        Instant soon = Instant.now().plus(7, ChronoUnit.DAYS);
        return visible().stream()
                .filter(e -> "EXPIRED".equals(e.getStatus())
                        || ("APPROVED".equals(e.getStatus()) && e.getExpiresAt() != null
                            && e.getExpiresAt().isBefore(soon)))
                .toList();
    }

    /** Active, approved and unexpired exceptions covering a data source — the "authorised" set. */
    public List<PiiExceptionEntity> activeFor(Long dataSourceId) {
        return repo.findByDataSourceIdOrderByCreatedAtDesc(dataSourceId).stream()
                .filter(e -> "APPROVED".equals(e.getStatus()))
                .filter(e -> e.getExpiresAt() != null && e.getExpiresAt().isAfter(Instant.now()))
                .filter(e -> ownership.canSee(e.getOwnerUserId(), e.getOwnerGroupId(), e.getVisibility()))
                .toList();
    }

    // --------------------------------------------------------------------- read

    public List<Map<String, Object>> list() {
        expireOverdue();
        return visible().stream()
                .sorted(Comparator.comparing(PiiExceptionEntity::getCreatedAt).reversed())
                .map(this::view)
                .toList();
    }

    public Map<String, Object> get(Long id) {
        return view(require(id));
    }

    public void delete(Long id) {
        PiiExceptionEntity e = require(id);
        if ("APPROVED".equals(e.getStatus())) {
            throw ApiException.bad("Revoke this exception before deleting it, so the decision stays on record.");
        }
        repo.delete(e);
        audit.record(actor(), "PII_EXCEPTION_DELETED", "SECURITY", "pii-exception",
                String.valueOf(id), e.getScope(), "SUCCESS", "Exception record deleted", null);
    }

    // ------------------------------------------------------------------ helpers

    private List<PiiExceptionEntity> visible() {
        return repo.findAllByOrderByCreatedAtDesc().stream()
                .filter(e -> ownership.canSee(e.getOwnerUserId(), e.getOwnerGroupId(), e.getVisibility()))
                .toList();
    }

    private PiiExceptionEntity require(Long id) {
        PiiExceptionEntity e = repo.findById(id)
                .orElseThrow(() -> ApiException.notFound("Exception " + id + " not found"));
        ownership.assertCanSee("PII exception", id, e.getOwnerUserId(), e.getOwnerGroupId(), e.getVisibility());
        return e;
    }

    Map<String, Object> view(PiiExceptionEntity e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", e.getId());
        m.put("dataSourceId", e.getDataSourceId());
        m.put("dataSourceName", safeSourceName(e.getDataSourceId()));
        m.put("environment", e.getEnvironment());
        m.put("scope", e.getScope());
        m.put("piiType", e.getPiiType());
        m.put("justification", e.getJustification());
        m.put("compensatingControls", e.getCompensatingControls());
        m.put("requestedBy", e.getRequestedBy());
        m.put("approvedBy", e.getApprovedBy());
        m.put("approvedAt", e.getApprovedAt());
        m.put("expiresAt", e.getExpiresAt());
        m.put("status", e.getStatus());
        m.put("expired", e.isExpired() || "EXPIRED".equals(e.getStatus()));
        m.put("daysRemaining", e.getExpiresAt() == null ? null
                : ChronoUnit.DAYS.between(Instant.now(), e.getExpiresAt()));
        m.put("createdAt", e.getCreatedAt());
        return m;
    }

    private String safeSourceName(Long id) {
        try { return dataSources.get(id).getName(); }
        catch (RuntimeException e) { return "(data source " + id + " no longer registered)"; }
    }

    private String actor() {
        return ownership.caller().map(p -> p.username()).orElse("system");
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
}
