package io.forgetdm.provision.loader;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "db2_zos_load_profiles")
public class Db2ZosLoadProfileEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "data_source_id", nullable = false, unique = true) private Long dataSourceId;
    @Column(name = "mainframe_connection_id", nullable = false) private Long mainframeConnectionId;
    @Column(nullable = false, length = 8) private String subsystem;
    @Column(name = "work_hlq", nullable = false, length = 26) private String workHlq;
    @Column(name = "procedure_name", nullable = false, length = 8) private String procedureName = "DSNUPROC";
    @Column(name = "job_class", nullable = false, length = 1) private String jobClass = "A";
    @Column(name = "message_class", nullable = false, length = 1) private String messageClass = "X";
    @Column(name = "job_accounting", length = 64) private String jobAccounting;
    @Column(name = "work_unit", nullable = false, length = 8) private String workUnit = "SYSDA";
    @Column(name = "logging_mode", nullable = false, length = 24) private String loggingMode = "RECOVERABLE";
    @Column(name = "max_return_code", nullable = false) private int maxReturnCode;
    @Column(name = "poll_seconds", nullable = false) private int pollSeconds = 5;
    @Column(name = "timeout_seconds", nullable = false) private int timeoutSeconds = 3600;
    @Column(name = "cleanup_remote", nullable = false) private boolean cleanupRemote = true;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDataSourceId() { return dataSourceId; }
    public void setDataSourceId(Long dataSourceId) { this.dataSourceId = dataSourceId; }
    public Long getMainframeConnectionId() { return mainframeConnectionId; }
    public void setMainframeConnectionId(Long mainframeConnectionId) { this.mainframeConnectionId = mainframeConnectionId; }
    public String getSubsystem() { return subsystem; }
    public void setSubsystem(String subsystem) { this.subsystem = subsystem; }
    public String getWorkHlq() { return workHlq; }
    public void setWorkHlq(String workHlq) { this.workHlq = workHlq; }
    public String getProcedureName() { return procedureName; }
    public void setProcedureName(String procedureName) { this.procedureName = procedureName; }
    public String getJobClass() { return jobClass; }
    public void setJobClass(String jobClass) { this.jobClass = jobClass; }
    public String getMessageClass() { return messageClass; }
    public void setMessageClass(String messageClass) { this.messageClass = messageClass; }
    public String getJobAccounting() { return jobAccounting; }
    public void setJobAccounting(String jobAccounting) { this.jobAccounting = jobAccounting; }
    public String getWorkUnit() { return workUnit; }
    public void setWorkUnit(String workUnit) { this.workUnit = workUnit; }
    public String getLoggingMode() { return loggingMode; }
    public void setLoggingMode(String loggingMode) { this.loggingMode = loggingMode; }
    public int getMaxReturnCode() { return maxReturnCode; }
    public void setMaxReturnCode(int maxReturnCode) { this.maxReturnCode = maxReturnCode; }
    public int getPollSeconds() { return pollSeconds; }
    public void setPollSeconds(int pollSeconds) { this.pollSeconds = pollSeconds; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public boolean isCleanupRemote() { return cleanupRemote; }
    public void setCleanupRemote(boolean cleanupRemote) { this.cleanupRemote = cleanupRemote; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
