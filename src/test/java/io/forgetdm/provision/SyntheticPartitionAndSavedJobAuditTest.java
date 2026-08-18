package io.forgetdm.provision;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class SyntheticPartitionAndSavedJobAuditTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AccessPrincipal OWNER = new AccessPrincipal(15L, "synthetic-owner", "Synthetic Owner",
            Set.of("TDM_ENGINEER"), Set.of("synthetic.manage"), List.of());

    @Test
    void partitionCancelAndRetryRecordSafeStructuredEvidence() throws Exception {
        JdbcTemplate jdbc = database("partition");
        AuditService audit = mock(AuditService.class);
        SyntheticGenService service = service(jdbc, audit);
        String planJson = JSON.writeValueAsString(filePlan("partition-audit-dataset"));
        insertGenerationJob(jdbc, "run-cancel", planJson);
        insertGenerationJob(jdbc, "run-retry", planJson);
        insertPartition(jdbc, "part-cancel", "run-cancel", "RUNNING");
        insertPartition(jdbc, "part-retry", "run-retry", "FAILED");

        AccessContext.callAs(OWNER, null, () -> service.cancelPartition("run-cancel", "part-cancel"));
        AccessContext.callAs(OWNER, null, () -> service.retryPartition("run-retry", "part-retry"));

        verify(audit).record(eq("synthetic-owner"), eq("SYNTHETIC_PARTITION_CANCELLED"), eq("GENERATE"),
                eq("SYNTHETIC_PARTITION"), eq("part-cancel"), eq("customers #1"), eq("SUCCESS"),
                eq("Synthetic partition cancelled"), safeMetadata("RAW PARTITION FAILURE"));
        verify(audit).record(eq("synthetic-owner"), eq("SYNTHETIC_PARTITION_RETRIED"), eq("GENERATE"),
                eq("SYNTHETIC_PARTITION"), eq("part-retry"), eq("customers #1"), eq("SUCCESS"),
                eq("Synthetic partition queued for retry"), safeMetadata("RAW PARTITION FAILURE"));
    }

    @Test
    void savedJobLaunchRecordsCallerAndSavedJobIdentityWithoutPlanLiterals() throws Exception {
        JdbcTemplate jdbc = database("saved_job");
        AuditService audit = mock(AuditService.class);
        SyntheticGenService service = service(jdbc, audit);
        SyntheticGenService.GenPlan plan = new SyntheticGenService.GenPlan("saved-audit-dataset",
                List.of(new SyntheticGenService.GenTable("customers", 1L, List.of(
                        new SyntheticGenService.GenColumn("secret", "LITERAL", "DO_NOT_AUDIT_PLAN_LITERAL", null,
                                false, null, null, "VARCHAR", null, null)
                ))), 42L, "JSON", null, null, false, false, null, null, null, null,
                null, null, false, null, false, "SINGLE", null, null);
        Instant now = Instant.now();
        jdbc.update("INSERT INTO synthetic_saved_jobs(id,owner_user_id,owner_username,name,description,plan_json," +
                        "approval_status,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?)",
                "saved-001", 15L, "synthetic-owner", "Approved customer job", "SECRET DESCRIPTION",
                JSON.writeValueAsString(plan), "APPROVED", Timestamp.from(now), Timestamp.from(now));

        AccessContext.callAs(OWNER, null, () -> service.runSavedJob("saved-001"));

        verify(audit).record(eq("synthetic-owner"), eq("SYNTHETIC_JOB_RUN"), eq("GENERATE"),
                eq("SYNTHETIC_SAVED_JOB"), eq("saved-001"), eq("Approved customer job"), eq("SUCCESS"),
                eq("Launched reusable synthetic job"),
                safeMetadata("DO_NOT_AUDIT_PLAN_LITERAL", "SECRET DESCRIPTION"));
    }

    private static SyntheticGenService service(JdbcTemplate jdbc, AuditService audit) {
        return new SyntheticGenService(mock(DataSourceService.class), new ConnectionFactory(), jdbc, audit, 1, 2555);
    }

    private static SyntheticGenService.GenPlan filePlan(String dataset) {
        return new SyntheticGenService.GenPlan(dataset,
                List.of(new SyntheticGenService.GenTable("customers", 10L, List.of(
                        new SyntheticGenService.GenColumn("customer_id", "SEQUENCE", null, null,
                                true, null, null, "BIGINT", null, null)
                ))), 42L, "JSON", null, null, false, false, null, null, null, null,
                null, null, false, null, false, "LOCAL_PARTITIONED", 1, 10L);
    }

    private static void insertGenerationJob(JdbcTemplate jdbc, String id, String planJson) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO synthetic_generation_jobs(id,owner_user_id,owner_username,dataset,receiver,load_action," +
                        "table_count,planned_rows,status,cancel_requested,percent,stage,message,plan_json,started_at,updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, 15L, "synthetic-owner", "partition-audit-dataset", "JSON", "", 1, 10L,
                "RUNNING", false, 50, "Partition load", "Working", planJson,
                Timestamp.from(now), Timestamp.from(now));
    }

    private static void insertPartition(JdbcTemplate jdbc, String id, String jobId, String status) {
        Instant now = Instant.now();
        jdbc.update("INSERT INTO synthetic_job_partitions(id,job_id,partition_number,dependency_wave,table_name," +
                        "row_start,row_end,planned_rows,rows_completed,status,attempt_count,cancel_requested,error,created_at,updated_at) " +
                        "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, jobId, 1, 0, "customers", 1L, 10L, 10L, 4L, status, 2, false,
                "RAW PARTITION FAILURE", Timestamp.from(now), Timestamp.from(now));
    }

    private static String safeMetadata(String... forbidden) {
        return org.mockito.ArgumentMatchers.argThat(metadata -> {
            if (metadata == null) return false;
            for (String value : forbidden) {
                if (metadata.contains(value)) return false;
            }
            return true;
        });
    }

    private static JdbcTemplate database(String suffix) {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:synthetic_audit_" + suffix + "_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("CREATE TABLE synthetic_generation_jobs (" +
                "id VARCHAR(80) PRIMARY KEY, owner_user_id BIGINT, owner_username VARCHAR(120), dataset VARCHAR(200)," +
                "receiver VARCHAR(40), load_action VARCHAR(40), table_count INT DEFAULT 0, planned_rows BIGINT DEFAULT 0," +
                "status VARCHAR(40), cancel_requested BOOLEAN DEFAULT FALSE, percent INT DEFAULT 0, stage VARCHAR(120)," +
                "message VARCHAR(1000), detail VARCHAR(1000), current_table VARCHAR(200), table_rows_done BIGINT DEFAULT 0," +
                "table_rows_total BIGINT DEFAULT 0, rows_done BIGINT DEFAULT 0, rows_total BIGINT DEFAULT 0, error VARCHAR(2000)," +
                "plan_json TEXT, plan_hash VARCHAR(80), lineage_json TEXT, constraint_snapshot_json TEXT," +
                "approval_snapshot_json TEXT, result_json TEXT, started_at TIMESTAMP, finished_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE synthetic_job_partitions (" +
                "id VARCHAR(80) PRIMARY KEY, job_id VARCHAR(80), partition_number INT, dependency_wave INT," +
                "table_name VARCHAR(200), row_start BIGINT, row_end BIGINT, planned_rows BIGINT, rows_completed BIGINT," +
                "status VARCHAR(40), worker_id VARCHAR(200), attempt_count INT DEFAULT 0, cancel_requested BOOLEAN DEFAULT FALSE," +
                "error VARCHAR(2000), lease_expires_at TIMESTAMP, heartbeat_at TIMESTAMP, started_at TIMESTAMP," +
                "finished_at TIMESTAMP, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE synthetic_saved_jobs (" +
                "id VARCHAR(80) PRIMARY KEY, owner_user_id BIGINT, owner_username VARCHAR(120), name VARCHAR(200)," +
                "description VARCHAR(500), plan_json TEXT, last_run_job_id VARCHAR(80), approval_status VARCHAR(40)," +
                "approval_requested_at TIMESTAMP, approved_at TIMESTAMP, approved_by VARCHAR(120), approval_note VARCHAR(500)," +
                "created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE synthetic_generation_lineage (" +
                "job_id VARCHAR(80), saved_job_id VARCHAR(80), saved_job_name VARCHAR(200), owner_username VARCHAR(120)," +
                "plan_hash VARCHAR(80), dataset VARCHAR(200), receiver VARCHAR(40), target_data_source_id BIGINT," +
                "target_schema VARCHAR(200), row_count BIGINT, table_count INT, seed_value BIGINT, approval_status VARCHAR(40)," +
                "approved_by VARCHAR(120), approved_at TIMESTAMP, constraint_snapshot_json TEXT, plan_summary_json TEXT," +
                "created_at TIMESTAMP)");
        return jdbc;
    }
}
