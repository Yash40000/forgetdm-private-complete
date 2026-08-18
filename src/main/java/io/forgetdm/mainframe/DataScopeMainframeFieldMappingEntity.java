package io.forgetdm.mainframe;

import jakarta.persistence.*;

import java.time.Instant;

/** Binds one physical copybook field to one governed relational masking-policy rule. */
@Entity
@Table(name = "datascope_mainframe_field_mappings")
public class DataScopeMainframeFieldMappingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "asset_id", nullable = false) private Long assetId;
    @Column(name = "policy_id", nullable = false) private Long policyId;
    @Column(name = "policy_rule_id", nullable = false) private Long policyRuleId;
    @Column(name = "field_path", nullable = false) private String fieldPath;
    @Column(name = "ordinal_no", nullable = false) private int ordinalNo;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long v) { assetId = v; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long v) { policyId = v; }
    public Long getPolicyRuleId() { return policyRuleId; }
    public void setPolicyRuleId(Long v) { policyRuleId = v; }
    public String getFieldPath() { return fieldPath; }
    public void setFieldPath(String v) { fieldPath = v; }
    public int getOrdinalNo() { return ordinalNo; }
    public void setOrdinalNo(int v) { ordinalNo = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
