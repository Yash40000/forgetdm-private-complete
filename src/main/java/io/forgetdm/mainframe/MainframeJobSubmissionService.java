package io.forgetdm.mainframe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.OwnershipGuard;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

/** Shared, caller-authorized submission path used by both DataScope and Business Entity orchestration. */
@Service
public class MainframeJobSubmissionService {
    private final MainframeJobRepository jobs;
    private final MainframeJobFileRepository files;
    private final MainframeConnectionRepository connections;
    private final CopybookDefRepository copybooks;
    private final MainframeMaskingService masking;
    private final OwnershipGuard ownership;
    private final AuditService audit;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public MainframeJobSubmissionService(MainframeJobRepository jobs,
                                         MainframeJobFileRepository files,
                                         MainframeConnectionRepository connections,
                                         CopybookDefRepository copybooks,
                                         MainframeMaskingService masking,
                                         OwnershipGuard ownership,
                                         AuditService audit) {
        this.jobs = jobs;
        this.files = files;
        this.connections = connections;
        this.copybooks = copybooks;
        this.masking = masking;
        this.ownership = ownership;
        this.audit = audit;
    }

    @Transactional
    public MainframeJobEntity submit(Submission submission) {
        if (submission == null || submission.fileSpecs() == null || submission.fileSpecs().isEmpty()) {
            throw ApiException.bad("A mainframe job needs at least one resolved file");
        }
        requireConnection(submission.sourceConnectionId(), "source");
        requireConnection(submission.targetConnectionId(), "target");
        for (FileSpec file : submission.fileSpecs()) {
            if (file == null || file.sourceName() == null || file.sourceName().isBlank()) {
                throw ApiException.bad("Each mainframe job file needs an exact source name");
            }
            requireCopybook(file.copybookId());
            if (file.targetConnectionId() != null) requireConnection(file.targetConnectionId(), "target");
        }

        MainframeJobEntity job = new MainframeJobEntity();
        job.setName(submission.name() == null || submission.name().isBlank()
                ? "DataScope mainframe masking" : submission.name().trim());
        job.setSourceConnectionId(submission.sourceConnectionId());
        job.setTargetConnectionId(submission.targetConnectionId());
        job.setMaskingSeed(blank(submission.maskingSeed()));
        job.setPolicyId(submission.policyId());
        job.setDatasetId(submission.datasetId());
        job.setBusinessEntityId(submission.businessEntityId());
        job.setExecutionPlanId(submission.executionPlanId());
        job.setRunGroupId(blank(submission.runGroupId()));
        job.setManifestJson(writeJson(submission.manifest()));
        job.setCreatedBy(actor());
        job.setOwnerUserId(ownership.defaultOwnerUserId());
        job.setOwnerUsername(ownership.defaultOwnerUsername());
        job.setOwnerGroupId(ownership.defaultOwnerGroupId());
        job.setVisibility(ownership.defaultVisibility());
        job.setStatus("PENDING");
        job.setFilesTotal(submission.fileSpecs().size());
        job = jobs.save(job);

        int ordinal = 0;
        for (FileSpec spec : submission.fileSpecs()) {
            MainframeJobFileEntity file = new MainframeJobFileEntity();
            file.setJobId(job.getId());
            file.setSourceName(spec.sourceName().trim());
            file.setCopybookId(spec.copybookId());
            file.setAssetId(spec.assetId());
            file.setRecfm(spec.recfm() == null || spec.recfm().isBlank() ? "FB" : spec.recfm().trim().toUpperCase());
            file.setLrecl(spec.lrecl());
            file.setCodePage(blank(spec.codePage()));
            file.setTargetConnectionId(spec.targetConnectionId());
            file.setTargetName(blank(spec.targetName()));
            file.setMaskPlanJson(writeJson(spec.maskPlan()));
            file.setMappingCount(spec.maskPlan() == null ? 0 : spec.maskPlan().rules().size());
            file.setOrdinal(ordinal++);
            files.save(file);
        }

        audit.record(actor(), "DATASCOPE_MAINFRAME_JOB_CREATED", "DATASCOPE", "MAINFRAME_JOB",
                String.valueOf(job.getId()), job.getName(), "SUCCESS", "Created mainframe child job",
                "{\"datasetId\":" + value(job.getDatasetId()) + ",\"files\":" + job.getFilesTotal()
                        + ",\"runGroupId\":\"" + safe(job.getRunGroupId()) + "\"}");
        Long submittedId = job.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { masking.submitAsync(submittedId); }
            });
        } else {
            masking.submitAsync(submittedId);
        }
        return job;
    }

    private MainframeConnectionEntity requireConnection(Long id, String role) {
        if (id == null) throw ApiException.bad(role + " mainframe connection is required");
        MainframeConnectionEntity connection = connections.findById(id)
                .orElseThrow(() -> ApiException.notFound("Mainframe connection " + id + " not found"));
        MainframeOwnership.assertCanSee(ownership, "mainframe connection", id,
                connection.getOwnerUserId(), connection.getOwnerGroupId(), connection.getVisibility());
        return connection;
    }

    private CopybookDefEntity requireCopybook(Long id) {
        if (id == null) throw ApiException.bad("copybookId is required");
        CopybookDefEntity copybook = copybooks.findById(id)
                .orElseThrow(() -> ApiException.notFound("Copybook " + id + " not found"));
        MainframeOwnership.assertCanSee(ownership, "mainframe copybook", id,
                copybook.getOwnerUserId(), copybook.getOwnerGroupId(), copybook.getVisibility());
        return copybook;
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value == null ? Map.of() : value); }
        catch (Exception e) { throw ApiException.bad("Could not create mainframe run manifest: " + e.getMessage()); }
    }

    private static String actor() { return AccessContext.current().map(p -> p.username()).orElse("system"); }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String safe(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\""); }
    private static String value(Long value) { return value == null ? "null" : String.valueOf(value); }

    public record FileSpec(String sourceName, String targetName, Long copybookId, String recfm,
                           Integer lrecl, String codePage, Long targetConnectionId, Long assetId,
                           MainframeMaskPlan maskPlan) {}

    public record Submission(String name, Long sourceConnectionId, Long targetConnectionId,
                             String maskingSeed, Long policyId, Long datasetId, Long businessEntityId,
                             Long executionPlanId, String runGroupId, List<FileSpec> fileSpecs,
                             Map<String, Object> manifest) {}
}
