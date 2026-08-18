package io.forgetdm.compliance;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A registered, time-boxed exception permitting unmasked (or partially masked) production data in a
 * non-production environment. Every exception must be justified, approved by someone other than the
 * requester, and carry an expiry — an expired exception is reported as a FAIL finding rather than
 * silently becoming permanent, which is how "temporary" prod copies normally become audit findings.
 */
@Entity
@Table(name = "pii_exception")
public class PiiExceptionEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Column(name = "data_source_id", nullable = false) private Long dataSourceId;
    @Column(nullable = false) private String environment;
    @Column(nullable = false) private String scope;
    @Column(name = "pii_type") private String piiType;
    @Column(nullable = false) private String justification;
    @Column(name = "compensating_controls") private String compensatingControls;
    @Column(name = "requested_by", nullable = false) private String requestedBy;
    @Column(name = "approved_by") private String approvedBy;
    @Column(name = "approved_at") private Instant approvedAt;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt;

    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(nullable = false) private String visibility = "PRIVATE";

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long v) { dataSourceId = v; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String v) { environment = v; }
    public String getScope() { return scope; }
    public void setScope(String v) { scope = v; }
    public String getPiiType() { return piiType; }
    public void setPiiType(String v) { piiType = v; }
    public String getJustification() { return justification; }
    public void setJustification(String v) { justification = v; }
    public String getCompensatingControls() { return compensatingControls; }
    public void setCompensatingControls(String v) { compensatingControls = v; }
    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { requestedBy = v; }
    public String getApprovedBy() { return approvedBy; }
    public void setApprovedBy(String v) { approvedBy = v; }
    public Instant getApprovedAt() { return approvedAt; }
    public void setApprovedAt(Instant v) { approvedAt = v; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant v) { expiresAt = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }

    /** True when an approved exception has passed its expiry — a reportable control failure. */
    @Transient
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(Instant.now())
                && ("APPROVED".equals(status) || "PENDING".equals(status));
    }
}
