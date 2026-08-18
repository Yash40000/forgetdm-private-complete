package io.forgetdm.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.platform.ClusterLeaseService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataScopeJobAuditCoverageTest {

    @Test
    void crudRunAndScheduleRecordStructuredIdentityWithoutSavedSpecOrDescriptions() throws Exception {
        JdbcTemplate jdbc = database();
        ProvisioningService provisioning = mock(ProvisioningService.class);
        AuditService audit = mock(AuditService.class);
        DataScopeJobService service = new DataScopeJobService(jdbc, provisioning, audit,
                mock(ClusterLeaseService.class), mock(AccessControlService.class));
        ObjectMapper json = new ObjectMapper();
        String secretFilter = "customer_ssn = '999-88-7777'";
        var spec = json.readTree("{\"jobType\":\"SUBSET_MASK\",\"sourceId\":1,\"targetId\":2,"
                + "\"datasetId\":3,\"specJson\":{\"filter\":\"" + secretFilter + "\"}}");
        AccessPrincipal principal = new AccessPrincipal(11L, "scope-owner", "Scope Owner",
                Set.of("TDM_ENGINEER"), Set.of("provision.run"), List.of());

        Map<String, Object> created = AccessContext.callAs(principal, null,
                () -> service.save(new DataScopeJobService.SavedJobRequest(
                        "Customer subset job", "SECRET DESCRIPTION", spec)));
        String id = String.valueOf(created.get("id"));
        AccessContext.callAs(principal, null, () -> service.update(id,
                new DataScopeJobService.SavedJobRequest("Customer subset updated", "SECRET UPDATED", spec)));
        AccessContext.callAs(principal, null, () -> service.setSchedule(id,
                new DataScopeJobService.ScheduleRequest("0 0 2 * * *", "UTC", true)));

        ProvisionJobEntity submitted = mock(ProvisionJobEntity.class);
        when(submitted.getId()).thenReturn(501L);
        when(submitted.getStatus()).thenReturn("AWAITING_APPROVAL");
        when(submitted.getApprovalStatus()).thenReturn("PENDING");
        when(submitted.getMessage()).thenReturn("Awaiting approval");
        when(provisioning.submit(any())).thenReturn(submitted);
        Map<String, Object> run = AccessContext.callAs(principal, null, () -> service.run(id));
        assertEquals(501L, run.get("runId"));
        AccessContext.callAs(principal, null, () -> {
            service.delete(id);
            return null;
        });

        verify(audit).record(eq("scope-owner"), eq("DATASCOPE_JOB_SAVED"), eq("PROVISION"),
                eq("DATASCOPE_SAVED_JOB"), eq(id), eq("Customer subset job"), eq("SUCCESS"),
                eq("Created DataScope saved job"), safeMetadata(secretFilter));
        verify(audit).record(eq("scope-owner"), eq("DATASCOPE_JOB_UPDATED"), eq("PROVISION"),
                eq("DATASCOPE_SAVED_JOB"), eq(id), eq("Customer subset updated"), eq("SUCCESS"),
                eq("Updated DataScope saved job"), safeMetadata(secretFilter));
        verify(audit).record(eq("scope-owner"), eq("DATASCOPE_JOB_SCHEDULE_SET"), eq("PROVISION"),
                eq("DATASCOPE_SAVED_JOB"), eq(id), eq("Customer subset updated"), eq("SUCCESS"),
                eq("Updated DataScope saved-job schedule"), safeMetadata(secretFilter));
        verify(audit).record(eq("scope-owner"), eq("DATASCOPE_JOB_RUN"), eq("PROVISION"),
                eq("DATASCOPE_SAVED_JOB"), eq(id), eq("Customer subset updated"), eq("SUCCESS"),
                eq("Launched DataScope saved job"), safeMetadata(secretFilter));
        verify(audit).record(eq("scope-owner"), eq("DATASCOPE_JOB_DELETED"), eq("PROVISION"),
                eq("DATASCOPE_SAVED_JOB"), eq(id), eq("Customer subset updated"), eq("SUCCESS"),
                eq("Deleted DataScope saved job"), safeMetadata(secretFilter));
    }

    private static String safeMetadata(String forbidden) {
        return org.mockito.ArgumentMatchers.argThat(metadata ->
                metadata != null
                        && !metadata.contains(forbidden)
                        && !metadata.contains("SECRET DESCRIPTION")
                        && !metadata.contains("SECRET UPDATED"));
    }

    private static JdbcTemplate database() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:datascope_audit_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
                "sa", ""));
        jdbc.execute("CREATE TABLE datascope_saved_jobs (" +
                "id VARCHAR(80) PRIMARY KEY, owner_user_id BIGINT, owner_username VARCHAR(120) NOT NULL," +
                "name VARCHAR(200) NOT NULL, description VARCHAR(500), spec_json TEXT NOT NULL," +
                "last_run_job_id BIGINT, schedule_cron VARCHAR(120), schedule_zone VARCHAR(60)," +
                "schedule_enabled BOOLEAN DEFAULT FALSE NOT NULL, next_run_at TIMESTAMP, last_scheduled_run_at TIMESTAMP," +
                "self_service_enabled BOOLEAN DEFAULT FALSE NOT NULL, self_service_label VARCHAR(200)," +
                "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
        return jdbc;
    }
}
