package io.forgetdm.mainframe;

import io.forgetdm.common.ApiException;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Compiles enabled DataScope file assets into frozen, asynchronous mainframe child jobs. */
@Service
public class DataScopeMainframeRunService {
    private final DataSetService datasets;
    private final DataScopeMainframeAssetService assets;
    private final DataScopeMainframeFieldMappingService fieldMappings;
    private final MainframeJobSubmissionService submissions;

    public DataScopeMainframeRunService(DataSetService datasets,
                                        DataScopeMainframeAssetService assets,
                                        DataScopeMainframeFieldMappingService fieldMappings,
                                        MainframeJobSubmissionService submissions) {
        this.datasets = datasets;
        this.assets = assets;
        this.fieldMappings = fieldMappings;
        this.submissions = submissions;
    }

    public Map<String, Object> launch(Long datasetId, RunRequest request,
                                      Long businessEntityId, Long executionPlanId) {
        DataSetDefinitionEntity definition = datasets.get(datasetId);
        List<DataScopeMainframeAssetEntity> configured = assets.enabled(datasetId);
        Set<Long> selected = request == null || request.assetIds() == null
                ? Set.of() : new LinkedHashSet<>(request.assetIds());
        if (!selected.isEmpty()) configured.removeIf(asset -> !selected.contains(asset.getId()));
        if (configured.isEmpty()) throw ApiException.bad("DataScope '" + definition.getName() + "' has no enabled mainframe file assets to run");
        Long policyId = request != null && request.policyId() != null ? request.policyId() : definition.getPolicyId();
        if (policyId == null) {
            throw ApiException.bad("A governed masking policy is required for mainframe file masking");
        }

        // Resolve every pattern before creating any jobs. This freezes the manifest and prevents a
        // later resolution failure from leaving a partially submitted multi-connection run.
        List<ResolvedAsset> resolved = new ArrayList<>();
        for (DataScopeMainframeAssetEntity asset : configured) {
            if (asset.getTargetConnectionId() == null) {
                throw ApiException.bad("Mainframe asset '" + asset.getLogicalRole() + "' needs a target connection before launch");
            }
            List<DataScopeMainframeAssetService.ResolvedFile> files = assets.resolve(datasetId, asset.getId());
            MainframeMaskPlan maskPlan = fieldMappings.compile(datasetId, asset.getId(), policyId);
            resolved.add(new ResolvedAsset(asset, files, maskPlan));
        }

        String runGroupId = "mf-ds-" + datasetId + "-" + UUID.randomUUID();
        Map<Long, List<ResolvedAsset>> bySource = new LinkedHashMap<>();
        for (ResolvedAsset item : resolved) {
            bySource.computeIfAbsent(item.asset().getSourceConnectionId(), ignored -> new ArrayList<>()).add(item);
        }

        List<Map<String, Object>> childRuns = new ArrayList<>();
        int groupNo = 0;
        for (Map.Entry<Long, List<ResolvedAsset>> group : bySource.entrySet()) {
            groupNo++;
            List<MainframeJobSubmissionService.FileSpec> fileSpecs = new ArrayList<>();
            List<Map<String, Object>> manifestFiles = new ArrayList<>();
            Long defaultTarget = null;
            for (ResolvedAsset item : group.getValue()) {
                if (defaultTarget == null) defaultTarget = item.asset().getTargetConnectionId();
                for (DataScopeMainframeAssetService.ResolvedFile file : item.files()) {
                    fileSpecs.add(new MainframeJobSubmissionService.FileSpec(file.sourceName(), file.targetName(),
                            file.copybookId(), file.recfm(), file.lrecl(), file.codePage(), file.targetConnectionId(),
                            file.assetId(), item.maskPlan()));
                    Map<String, Object> manifestFile = new LinkedHashMap<>();
                    manifestFile.put("assetId", file.assetId());
                    manifestFile.put("logicalRole", file.logicalRole());
                    manifestFile.put("sourceName", file.sourceName());
                    manifestFile.put("targetName", file.targetName());
                    manifestFile.put("copybookId", file.copybookId());
                    manifestFile.put("recfm", file.recfm());
                    manifestFile.put("lrecl", file.lrecl());
                    manifestFile.put("codePage", file.codePage());
                    manifestFile.put("sourceSizeBytes", file.sizeBytes());
                    manifestFile.put("maskingRuleCount", item.maskPlan().rules().size());
                    manifestFiles.add(manifestFile);
                }
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("datasetId", datasetId);
            manifest.put("datasetName", definition.getName());
            manifest.put("scopeKind", definition.getScopeKind());
            manifest.put("policyId", policyId);
            manifest.put("runGroupId", runGroupId);
            manifest.put("businessEntityId", businessEntityId);
            manifest.put("executionPlanId", executionPlanId);
            manifest.put("files", manifestFiles);

            String baseName = request == null || request.name() == null || request.name().isBlank()
                    ? definition.getName() + " mainframe run" : request.name().trim();
            String jobName = bySource.size() == 1 ? baseName : baseName + " / source " + groupNo;
            MainframeJobEntity job = submissions.submit(new MainframeJobSubmissionService.Submission(
                    jobName, group.getKey(), defaultTarget,
                    request == null ? null : request.maskingSeed(), policyId, datasetId, businessEntityId,
                    executionPlanId, runGroupId, fileSpecs, manifest));
            Map<String, Object> child = new LinkedHashMap<>();
            child.put("engine", "MAINFRAME");
            child.put("runId", job.getId());
            child.put("status", job.getStatus());
            child.put("sourceConnectionId", job.getSourceConnectionId());
            child.put("files", job.getFilesTotal());
            childRuns.add(child);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("engine", "MAINFRAME_FANOUT");
        result.put("runId", runGroupId);
        result.put("runGroupId", runGroupId);
        result.put("status", "SUBMITTED");
        result.put("datasetId", datasetId);
        result.put("assetCount", resolved.size());
        result.put("fileCount", childRuns.stream().mapToInt(row -> ((Number) row.get("files")).intValue()).sum());
        result.put("runs", childRuns);
        result.put("message", "Submitted " + childRuns.size() + " mainframe child job(s)");
        return result;
    }

    public boolean hasEnabledAssets(Long datasetId) {
        return !assets.enabled(datasetId).isEmpty();
    }

    public record RunRequest(String name, List<Long> assetIds, String maskingSeed, Long policyId) {
        public RunRequest(String name, List<Long> assetIds, String maskingSeed) {
            this(name, assetIds, maskingSeed, null);
        }
    }
    private record ResolvedAsset(DataScopeMainframeAssetEntity asset,
                                 List<DataScopeMainframeAssetService.ResolvedFile> files,
                                 MainframeMaskPlan maskPlan) {}
}
