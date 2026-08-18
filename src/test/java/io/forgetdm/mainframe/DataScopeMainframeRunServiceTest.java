package io.forgetdm.mainframe;

import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DataScopeMainframeRunServiceTest {

    @Test
    void groupsMultipleFileAssetsFromOneSourceIntoOneChildJob() {
        DataSetService datasets = mock(DataSetService.class);
        DataScopeMainframeAssetService assets = mock(DataScopeMainframeAssetService.class);
        DataScopeMainframeFieldMappingService fieldMappings = mock(DataScopeMainframeFieldMappingService.class);
        MainframeJobSubmissionService submissions = mock(MainframeJobSubmissionService.class);
        DataSetDefinitionEntity definition = new DataSetDefinitionEntity();
        definition.setName("customer-hybrid");
        definition.setScopeKind("HYBRID");
        definition.setPolicyId(41L);
        when(datasets.get(51L)).thenReturn(definition);

        DataScopeMainframeAssetEntity master = asset(201L, "master", 7L, 9L);
        DataScopeMainframeAssetEntity cards = asset(202L, "cards", 7L, 10L);
        when(assets.enabled(51L)).thenReturn(new java.util.ArrayList<>(List.of(master, cards)));
        when(assets.resolve(51L, 201L)).thenReturn(List.of(
                new DataScopeMainframeAssetService.ResolvedFile(201L, "master", "PROD.MASTER", "TEST.MASTER",
                        "FB", 42, "Cp037", 420L, "PS", 9L, 8L)));
        when(assets.resolve(51L, 202L)).thenReturn(List.of(
                new DataScopeMainframeAssetService.ResolvedFile(202L, "cards", "PROD.CARDS", "TEST.CARDS",
                        "VB", 120, "Cp037", 900L, "PS", 10L, 8L)));
        when(fieldMappings.compile(eq(51L), anyLong(), eq(41L))).thenAnswer(call ->
                new MainframeMaskPlan(41L, call.getArgument(1), List.of(
                        new MainframeMaskPlan.Rule("CUSTOMER-ID", 501L, "CUSTOMER", "CUSTOMER_ID",
                                "FORMAT_PRESERVE", null, null, "customer.id"))));
        when(submissions.submit(any())).thenAnswer(call -> {
            MainframeJobSubmissionService.Submission submission = call.getArgument(0);
            MainframeJobEntity job = new MainframeJobEntity();
            job.setId(301L);
            job.setStatus("PENDING");
            job.setSourceConnectionId(submission.sourceConnectionId());
            job.setFilesTotal(submission.fileSpecs().size());
            return job;
        });

        DataScopeMainframeRunService service = new DataScopeMainframeRunService(
                datasets, assets, fieldMappings, submissions);
        Map<String, Object> result = service.launch(51L,
                new DataScopeMainframeRunService.RunRequest("Customer run", null, "stable-seed"), 1L, 77L);

        assertEquals("SUBMITTED", result.get("status"));
        assertEquals(2, result.get("assetCount"));
        assertEquals(2, result.get("fileCount"));
        verify(submissions).submit(argThat(submission -> submission.fileSpecs().size() == 2
                && submission.businessEntityId().equals(1L)
                && submission.policyId().equals(41L)
                && submission.executionPlanId().equals(77L)));
    }

    private static DataScopeMainframeAssetEntity asset(Long id, String role, Long source, Long copybook) {
        DataScopeMainframeAssetEntity asset = new DataScopeMainframeAssetEntity();
        asset.setId(id);
        asset.setDatasetId(51L);
        asset.setLogicalRole(role);
        asset.setSourceConnectionId(source);
        asset.setTargetConnectionId(8L);
        asset.setCopybookId(copybook);
        asset.setSourceNamePattern("PROD." + role.toUpperCase());
        asset.setSelectionMode("ALL");
        asset.setEnabled(true);
        return asset;
    }
}
