package io.forgetdm.mainframe;

import jakarta.persistence.*;

import java.time.Instant;

/** Control-plane definition for one mainframe file (or resolvable file pattern) in a DataScope. */
@Entity
@Table(name = "datascope_mainframe_assets")
public class DataScopeMainframeAssetEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "dataset_id", nullable = false) private Long datasetId;
    @Column(name = "logical_role", nullable = false) private String logicalRole;
    @Column(name = "source_connection_id", nullable = false) private Long sourceConnectionId;
    @Column(name = "target_connection_id") private Long targetConnectionId;
    @Column(name = "source_name_pattern", nullable = false) private String sourceNamePattern;
    @Column(name = "target_name_template") private String targetNameTemplate;
    @Column(name = "copybook_id", nullable = false) private Long copybookId;
    @Column(nullable = false) private String dsorg = "PS";
    @Column(nullable = false) private String recfm = "FB";
    private Integer lrecl;
    @Column(name = "code_page") private String codePage;
    @Column(name = "selection_mode", nullable = false) private String selectionMode = "ALL";
    /** Comma-separated copybook field paths forming the file's logical primary key. */
    @Column(name = "key_field_paths", columnDefinition = "text") private String keyFieldPaths;
    @Column(name = "entity_key_field_path") private String entityKeyFieldPath;
    @Column(name = "filter_expression", columnDefinition = "text") private String filterExpression;
    @Column(nullable = false) private boolean enabled = true;
    @Column(name = "ordinal_no", nullable = false) private int ordinalNo;
    @Column(name = "created_at") private Instant createdAt = Instant.now();
    @Column(name = "updated_at") private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long v) { id = v; }
    public Long getDatasetId() { return datasetId; }
    public void setDatasetId(Long v) { datasetId = v; }
    public String getLogicalRole() { return logicalRole; }
    public void setLogicalRole(String v) { logicalRole = v; }
    public Long getSourceConnectionId() { return sourceConnectionId; }
    public void setSourceConnectionId(Long v) { sourceConnectionId = v; }
    public Long getTargetConnectionId() { return targetConnectionId; }
    public void setTargetConnectionId(Long v) { targetConnectionId = v; }
    public String getSourceNamePattern() { return sourceNamePattern; }
    public void setSourceNamePattern(String v) { sourceNamePattern = v; }
    public String getTargetNameTemplate() { return targetNameTemplate; }
    public void setTargetNameTemplate(String v) { targetNameTemplate = v; }
    public Long getCopybookId() { return copybookId; }
    public void setCopybookId(Long v) { copybookId = v; }
    public String getDsorg() { return dsorg; }
    public void setDsorg(String v) { dsorg = v; }
    public String getRecfm() { return recfm; }
    public void setRecfm(String v) { recfm = v; }
    public Integer getLrecl() { return lrecl; }
    public void setLrecl(Integer v) { lrecl = v; }
    public String getCodePage() { return codePage; }
    public void setCodePage(String v) { codePage = v; }
    public String getSelectionMode() { return selectionMode; }
    public void setSelectionMode(String v) { selectionMode = v; }
    public String getKeyFieldPaths() { return keyFieldPaths; }
    public void setKeyFieldPaths(String v) { keyFieldPaths = v; }
    public String getEntityKeyFieldPath() { return entityKeyFieldPath; }
    public void setEntityKeyFieldPath(String v) { entityKeyFieldPath = v; }
    public String getFilterExpression() { return filterExpression; }
    public void setFilterExpression(String v) { filterExpression = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { enabled = v; }
    public int getOrdinalNo() { return ordinalNo; }
    public void setOrdinalNo(int v) { ordinalNo = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { createdAt = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { updatedAt = v; }
}
