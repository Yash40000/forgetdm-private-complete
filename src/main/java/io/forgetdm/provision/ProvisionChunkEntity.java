package io.forgetdm.provision;

import jakarta.persistence.*;

import java.time.Instant;

/** Durable checkpoint for one committed provisioning chunk. */
@Entity
@Table(name = "provision_job_chunks",
        uniqueConstraints = @UniqueConstraint(name = "uq_provision_job_chunk",
                columnNames = {"job_id", "table_name", "chunk_no"}))
public class ProvisionChunkEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "job_id", nullable = false) private Long jobId;
    @Column(name = "table_name", nullable = false, length = 512) private String tableName;
    @Column(name = "chunk_no", nullable = false) private int chunkNo;
    @Column(nullable = false, length = 30) private String state = "RUNNING";
    @Column(name = "resume_key", length = 2000) private String resumeKey;
    @Column(name = "rows_read") private long rowsRead;
    @Column(name = "rows_written") private long rowsWritten;
    @Column(name = "bytes_staged") private long bytesStaged;
    @Column(name = "loader_strategy", length = 100) private String loaderStrategy;
    @Column(name = "cursor_mode", length = 100) private String cursorMode;
    @Column(nullable = false) private int attempts = 1;
    @Column(name = "started_at") private Instant startedAt = Instant.now();
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "error_message", columnDefinition = "text") private String errorMessage;
    @Column(name = "table_complete", nullable = false) private boolean tableComplete;

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long value) { jobId = value; }
    public String getTableName() { return tableName; }
    public void setTableName(String value) { tableName = value; }
    public int getChunkNo() { return chunkNo; }
    public void setChunkNo(int value) { chunkNo = value; }
    public String getState() { return state; }
    public void setState(String value) { state = value; }
    public String getResumeKey() { return resumeKey; }
    public void setResumeKey(String value) { resumeKey = value; }
    public long getRowsRead() { return rowsRead; }
    public void setRowsRead(long value) { rowsRead = value; }
    public long getRowsWritten() { return rowsWritten; }
    public void setRowsWritten(long value) { rowsWritten = value; }
    public long getBytesStaged() { return bytesStaged; }
    public void setBytesStaged(long value) { bytesStaged = value; }
    public String getLoaderStrategy() { return loaderStrategy; }
    public void setLoaderStrategy(String value) { loaderStrategy = value; }
    public String getCursorMode() { return cursorMode; }
    public void setCursorMode(String value) { cursorMode = value; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int value) { attempts = value; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant value) { startedAt = value; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant value) { finishedAt = value; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String value) { errorMessage = value; }
    public boolean isTableComplete() { return tableComplete; }
    public void setTableComplete(boolean value) { tableComplete = value; }
}
