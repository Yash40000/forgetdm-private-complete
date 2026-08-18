package io.forgetdm.compliance;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One compliance assurance run. A scan proves something an auditor asked for — masking coverage,
 * absence of real PII, masked-value cardinality, or whether a data subject is reachable at all.
 */
@Entity
@Table(name = "compliance_scan")
public class ComplianceScanEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;

    @Column(name = "scan_type", nullable = false) private String scanType;
    private String name;
    private String environment;
    @Column(name = "target_data_source_id") private Long targetDataSourceId;
    @Column(name = "source_data_source_id") private Long sourceDataSourceId;
    @Column(name = "policy_id") private Long policyId;
    @Column(name = "schema_name") private String schemaName;
    @Column(name = "subject_value_hash") private String subjectValueHash;
    @Column(nullable = false) private String status = "RUNNING";
    private String result;
    @Column(name = "columns_scanned", nullable = false) private int columnsScanned;
    @Column(name = "rows_scanned", nullable = false) private long rowsScanned;
    @Column(name = "fail_count", nullable = false) private int failCount;
    @Column(name = "warn_count", nullable = false) private int warnCount;
    private String summary;
    private String error;
    @Column(name = "evidence_json") private String evidenceJson;
    @Column(name = "started_at", nullable = false) private Instant startedAt = Instant.now();
    @Column(name = "finished_at") private Instant finishedAt;

    @Column(name = "owner_user_id") private Long ownerUserId;
    @Column(name = "owner_username") private String ownerUsername;
    @Column(name = "owner_group_id") private Long ownerGroupId;
    @Column(nullable = false) private String visibility = "PRIVATE";

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public String getScanType() { return scanType; }
    public void setScanType(String v) { scanType = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String v) { environment = v; }
    public Long getTargetDataSourceId() { return targetDataSourceId; }
    public void setTargetDataSourceId(Long v) { targetDataSourceId = v; }
    public Long getSourceDataSourceId() { return sourceDataSourceId; }
    public void setSourceDataSourceId(Long v) { sourceDataSourceId = v; }
    public Long getPolicyId() { return policyId; }
    public void setPolicyId(Long v) { policyId = v; }
    public String getSchemaName() { return schemaName; }
    public void setSchemaName(String v) { schemaName = v; }
    public String getSubjectValueHash() { return subjectValueHash; }
    public void setSubjectValueHash(String v) { subjectValueHash = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public String getResult() { return result; }
    public void setResult(String v) { result = v; }
    public int getColumnsScanned() { return columnsScanned; }
    public void setColumnsScanned(int v) { columnsScanned = v; }
    public long getRowsScanned() { return rowsScanned; }
    public void setRowsScanned(long v) { rowsScanned = v; }
    public int getFailCount() { return failCount; }
    public void setFailCount(int v) { failCount = v; }
    public int getWarnCount() { return warnCount; }
    public void setWarnCount(int v) { warnCount = v; }
    public String getSummary() { return summary; }
    public void setSummary(String v) { summary = v; }
    public String getError() { return error; }
    public void setError(String v) { error = v; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String v) { evidenceJson = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { finishedAt = v; }
    public Long getOwnerUserId() { return ownerUserId; }
    public void setOwnerUserId(Long v) { ownerUserId = v; }
    public String getOwnerUsername() { return ownerUsername; }
    public void setOwnerUsername(String v) { ownerUsername = v; }
    public Long getOwnerGroupId() { return ownerGroupId; }
    public void setOwnerGroupId(Long v) { ownerGroupId = v; }
    public String getVisibility() { return visibility; }
    public void setVisibility(String v) { visibility = v; }
}
