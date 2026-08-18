package io.forgetdm.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyntheticDirectGenerationAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void directGenerationWritesStructuredStartAndCompletedAuditWithoutPlanPayload() throws Exception {
        AuditService audit = mock(AuditService.class);
        SyntheticGenService gen = service(audit);
        SyntheticGenService.GenPlan plan = filePlan("aud-direct-ok",
                List.of(new SyntheticGenService.GenTable("customers", 2L, List.of(
                        new SyntheticGenService.GenColumn("customer_id", "SEQUENCE", null, null,
                                true, null, null, "BIGINT", null, null),
                        new SyntheticGenService.GenColumn("secret_literal", "LITERAL", "DO_NOT_AUDIT_THIS_LITERAL", null,
                                false, null, null, "VARCHAR", null, null)
                ))));

        Map<String, Object> result = gen.generate(plan);

        assertTrue(result.containsKey("files"));
        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> resourceName = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(audit, times(2)).record(anyString(), action.capture(), eq("GENERATE"), resourceType.capture(),
                anyString(), resourceName.capture(), outcome.capture(), detail.capture(), metadata.capture());

        assertEquals(List.of("SYNTHETIC_DIRECT_GENERATION_STARTED", "SYNTHETIC_DIRECT_GENERATION_COMPLETED"), action.getAllValues());
        assertEquals(List.of("SYNTHETIC_DIRECT_RUN", "SYNTHETIC_DIRECT_RUN"), resourceType.getAllValues());
        assertEquals(List.of("aud-direct-ok", "aud-direct-ok"), resourceName.getAllValues());
        assertEquals(List.of("SUCCESS", "SUCCESS"), outcome.getAllValues());
        for (String payload : metadata.getAllValues()) {
            assertFalse(payload.contains("DO_NOT_AUDIT_THIS_LITERAL"), payload);
            Map<?, ?> parsed = JSON.readValue(payload, Map.class);
            assertEquals("JSON", parsed.get("receiver"));
            assertEquals(1, parsed.get("tableCount"));
            assertEquals(2, parsed.get("plannedRows"));
            assertTrue(String.valueOf(parsed.get("planHash")).matches("[0-9a-f]{64}"));
        }
        assertTrue(detail.getAllValues().stream().allMatch(value -> value.startsWith("Direct synthetic generation ")));
    }

    @Test
    void directGenerationWritesStructuredFailureAuditWithoutRawErrorOrRows() throws Exception {
        AuditService audit = mock(AuditService.class);
        DataSourceService dataSources = mock(DataSourceService.class);
        when(dataSources.getTargetCapable(99L)).thenThrow(ApiException.bad("target not allowed SECRET_PARAM"));
        SyntheticGenService gen = service(audit, dataSources);
        SyntheticGenService.GenPlan plan = new SyntheticGenService.GenPlan("aud-direct-fail",
                List.of(new SyntheticGenService.GenTable("customers", 1L, List.of(
                        new SyntheticGenService.GenColumn("customer_id", "SEQUENCE", null, null,
                                true, null, null, "BIGINT", null, null)
                ))), 42L, "DB", 99L, "target_schema", false, false, null, null, null, null,
                null, null, false, null, false, "SINGLE", null, null);

        assertThrows(ApiException.class, () -> gen.generate(plan));

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> outcome = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> metadata = ArgumentCaptor.forClass(String.class);
        verify(audit, times(2)).record(anyString(), action.capture(), eq("GENERATE"), eq("SYNTHETIC_DIRECT_RUN"),
                anyString(), eq("aud-direct-fail"), outcome.capture(), anyString(), metadata.capture());

        assertEquals(List.of("SYNTHETIC_DIRECT_GENERATION_STARTED", "SYNTHETIC_DIRECT_GENERATION_FAILED"), action.getAllValues());
        assertEquals(List.of("SUCCESS", "FAILURE"), outcome.getAllValues());
        String failedMetadata = metadata.getAllValues().get(1);
        assertFalse(failedMetadata.contains("SECRET_PARAM"), failedMetadata);
        Map<?, ?> parsed = JSON.readValue(failedMetadata, Map.class);
        assertEquals("FAILED", parsed.get("status"));
        assertEquals("ApiException", parsed.get("errorType"));
        assertEquals(1, parsed.get("plannedRows"));
        assertEquals(99, parsed.get("targetDataSourceId"));
    }

    private static SyntheticGenService service(AuditService audit) {
        return service(audit, mock(DataSourceService.class));
    }

    private static SyntheticGenService service(AuditService audit, DataSourceService dataSources) {
        return new SyntheticGenService(dataSources, new ConnectionFactory(),
                mock(JdbcTemplate.class), audit, 1, 2555);
    }

    private static SyntheticGenService.GenPlan filePlan(String dataset, List<SyntheticGenService.GenTable> tables) {
        return new SyntheticGenService.GenPlan(dataset, tables, 42L, "JSON",
                null, null, false, false, null, null, null, null,
                null, null, false, null, false, "SINGLE", null, null);
    }
}
