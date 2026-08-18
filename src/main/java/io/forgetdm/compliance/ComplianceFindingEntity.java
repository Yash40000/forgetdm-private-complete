package io.forgetdm.compliance;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A single compliance finding. Deliberately holds no raw PII: a witness value is recorded only as
 * a salted hash so the evidence trail can be shared with an auditor without leaking the data.
 */
@Entity
@Table(name = "compliance_finding")
public class ComplianceFindingEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Column(name = "scan_id", nullable = false) private Long scanId;
    @Column(nullable = false) private String severity;
    @Column(name = "check_name", nullable = false) private String checkName;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "table_name") private String tableName;
    @Column(name = "column_name") private String columnName;
    @Column(name = "pii_type") private String piiType;
    @Column(name = "affected_rows", nullable = false) private long affectedRows;
    @Column(nullable = false) private String detail;
    private String remediation;
    @Column(name = "evidence_hash") private String evidenceHash;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getScanId() { return scanId; }
    public void setScanId(Long v) { scanId = v; }
    public String getSeverity() { return severity; }
    public void setSeverity(String v) { severity = v; }
    public String getCheckName() { return checkName; }
    public void setCheckName(String v) { checkName = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { schemaName = v; }
    public String getTableName() { return tableName; }
    public void setTableName(String v) { tableName = v; }
    public String getColumnName() { return columnName; }
    public void setColumnName(String v) { columnName = v; }
    public String getPiiType() { return piiType; }
    public void setPiiType(String v) { piiType = v; }
    public long getAffectedRows() { return affectedRows; }
    public void setAffectedRows(long v) { affectedRows = v; }
    public String getDetail() { return detail; }
    public void setDetail(String v) { detail = v; }
    public String getRemediation() { return remediation; }
    public void setRemediation(String v) { remediation = v; }
    public String getEvidenceHash() { return evidenceHash; }
    public void setEvidenceHash(String v) { evidenceHash = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
}
