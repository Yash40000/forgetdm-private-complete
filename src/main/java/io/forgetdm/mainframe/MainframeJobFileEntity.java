package io.forgetdm.mainframe;

import jakarta.persistence.*;

import java.time.Instant;

/** One file within a mainframe job, with its own copybook, record format, and optional target LPAR/name. */
@Entity
@Table(name = "mf_job_files")
public class MainframeJobFileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "job_id", nullable = false) private Long jobId;
    @Column(name = "source_name", nullable = false) private String sourceName;
    @Column(name = "copybook_id") private Long copybookId;
    @Column(name = "asset_id") private Long assetId;
    @Column(nullable = false) private String recfm = "FB";       // FB | VB
    private Integer lrecl;
    @Column(name = "code_page") private String codePage;
    @Column(name = "target_connection_id") private Long targetConnectionId; // null = job target
    @Column(name = "target_name") private String targetName;     // null = same as source
    @Column(nullable = false) private String status = "PENDING";
    @Column(name = "record_count") private long recordCount;
    @Column(name = "records_processed", nullable = false) private long recordsProcessed;
    @Column(name = "checkpoint_record", nullable = false) private long checkpointRecord;
    @Column(name = "input_bytes", nullable = false) private long inputBytes;
    @Column(name = "output_bytes", nullable = false) private long outputBytes;
    @Column(name = "input_sha256") private String inputSha256;
    @Column(name = "output_sha256") private String outputSha256;
    @Column(name = "source_version") private String sourceVersion;
    @Column(name = "target_version") private String targetVersion;
    @Column(name = "staging_name") private String stagingName;
    @Column(name = "started_at") private Instant startedAt;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "mask_plan_json", columnDefinition = "text") private String maskPlanJson;
    @Column(name = "mapping_count", nullable = false) private int mappingCount;
    @Column(columnDefinition = "text") private String message;
    @Column(nullable = false) private int ordinal;

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long v) { jobId = v; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String v) { sourceName = v; }
    public Long getCopybookId() { return copybookId; }
    public void setCopybookId(Long v) { copybookId = v; }
    public Long getAssetId() { return assetId; }
    public void setAssetId(Long v) { assetId = v; }
    public String getRecfm() { return recfm; }
    public void setRecfm(String v) { recfm = v; }
    public Integer getLrecl() { return lrecl; }
    public void setLrecl(Integer v) { lrecl = v; }
    public String getCodePage() { return codePage; }
    public void setCodePage(String v) { codePage = v; }
    public Long getTargetConnectionId() { return targetConnectionId; }
    public void setTargetConnectionId(Long v) { targetConnectionId = v; }
    public String getTargetName() { return targetName; }
    public void setTargetName(String v) { targetName = v; }
    public String getStatus() { return status; }
    public void setStatus(String v) { status = v; }
    public long getRecordCount() { return recordCount; }
    public void setRecordCount(long v) { recordCount = v; }
    public long getRecordsProcessed() { return recordsProcessed; }
    public void setRecordsProcessed(long v) { recordsProcessed = v; }
    public long getCheckpointRecord() { return checkpointRecord; }
    public void setCheckpointRecord(long v) { checkpointRecord = v; }
    public long getInputBytes() { return inputBytes; }
    public void setInputBytes(long v) { inputBytes = v; }
    public long getOutputBytes() { return outputBytes; }
    public void setOutputBytes(long v) { outputBytes = v; }
    public String getInputSha256() { return inputSha256; }
    public void setInputSha256(String v) { inputSha256 = v; }
    public String getOutputSha256() { return outputSha256; }
    public void setOutputSha256(String v) { outputSha256 = v; }
    public String getSourceVersion() { return sourceVersion; }
    public void setSourceVersion(String v) { sourceVersion = v; }
    public String getTargetVersion() { return targetVersion; }
    public void setTargetVersion(String v) { targetVersion = v; }
    public String getStagingName() { return stagingName; }
    public void setStagingName(String v) { stagingName = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { startedAt = v; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { finishedAt = v; }
    public String getMaskPlanJson() { return maskPlanJson; }
    public void setMaskPlanJson(String v) { maskPlanJson = v; }
    public int getMappingCount() { return mappingCount; }
    public void setMappingCount(int v) { mappingCount = v; }
    public String getMessage() { return message; }
    public void setMessage(String v) { message = v; }
    public int getOrdinal() { return ordinal; }
    public void setOrdinal(int v) { ordinal = v; }
}
