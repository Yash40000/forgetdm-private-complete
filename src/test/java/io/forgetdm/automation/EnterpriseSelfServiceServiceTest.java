package io.forgetdm.automation;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.mapping.MappingExecutionService;
import io.forgetdm.provision.DataScopeJobService;
import io.forgetdm.provision.ProvisioningService;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.reservation.ReservationService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.virtualization.VirtualizationService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class EnterpriseSelfServiceServiceTest {
    @Test
    void productQuestionnaireMakerCheckerAndExecutionStayGoverned() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:self_service_v2_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        schema(jdbc);
        jdbc.update("INSERT INTO forge_users(id,username) VALUES (10,'tester'),(20,'approver'),(30,'architect')");
        jdbc.update("INSERT INTO datascope_saved_jobs(id,name,description,owner_user_id,owner_username,self_service_enabled,self_service_label,updated_at) VALUES ('template-1','Customer QA','Masked customer slice',30,'architect',FALSE,NULL,CURRENT_TIMESTAMP)");
        DataScopeJobService jobs = mock(DataScopeJobService.class);
        when(jobs.runSelfService(eq("template-1"), anyMap(), any(ProvisioningService.ApprovalEvidence.class)))
                .thenReturn(Map.of("runId", 91L, "status", "QUEUED"));
        AuditService audit = mock(AuditService.class);
        EnterpriseSelfServiceService service = new EnterpriseSelfServiceService(jdbc, new ObjectMapper(), jobs,
                mock(SyntheticGenService.class), mock(MappingExecutionService.class), mock(ReservationService.class),
                mock(VirtualizationService.class), mock(IntegrationWebhookService.class), audit);
        AccessPrincipal architect = new AccessPrincipal(30L, "architect", "Architect", Set.of("TDM_ARCHITECT"), Set.of("datascope.manage", "provision.read", "provision.run"));
        AccessPrincipal tester = new AccessPrincipal(10L, "tester", "Tester", Set.of("TESTER"), Set.of("provision.read", "provision.run"));
        AccessPrincipal approver = new AccessPrincipal(20L, "approver", "Approver", Set.of("ADMIN"), Set.of("admin.all", "provision.approve", "provision.read", "provision.run"));

        Map<String, Object> product = AccessContext.callAs(architect, null, () -> service.publish(
                new EnterpriseSelfServiceService.ProductRequest("DATASCOPE", "template-1", 1, "Customer 360 QA",
                        "Approved masked customer data", "Customer", "regression,masked", true, "REQUIRED",
                        new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode().put("maxVolume", 1000),
                        List.of("QA", "UAT"), "Use in the assigned QA environment.")));
        String productId = String.valueOf(product.get("id"));
        Map<String, Object> order = AccessContext.callAs(tester, null, () -> service.request(
                new EnterpriseSelfServiceService.OrderRequest(productId, "Sprint regression", "REGRESSION", "QA",
                        Map.of("selectionNote", "Active retail customer"), 500L, null, "DATABASE", false, null, null)));
        String orderId = String.valueOf(order.get("id"));
        AccessContext.callAs(approver, null, () -> service.decide(orderId, true, new EnterpriseSelfServiceService.Decision("Approved for sprint 52")));
        Map<String, Object> fulfilled = AccessContext.callAs(tester, null, () -> service.fulfill(orderId));

        assertEquals("SUBMITTED", fulfilled.get("status"));
        assertEquals("91", String.valueOf(fulfilled.get("runRef")));
        assertEquals(3, ((List<?>) fulfilled.get("events")).size());
        verify(jobs).runSelfService(eq("template-1"), argThat(parameters ->
                        "Active retail customer".equals(parameters.get("selectionNote"))
                                && "500".equals(String.valueOf(parameters.get("_requestedVolume")))),
                argThat(evidence -> "SELF_SERVICE_ORDER".equals(evidence.source())
                        && orderId.equals(evidence.reference())
                        && "approver".equals(evidence.approvedBy())));
        verify(audit).record(eq("architect"), eq("SELF_SERVICE_PRODUCT_PUBLISHED"), eq("SELF_SERVICE"),
                eq("self-service-product"), eq(productId), eq("Customer 360 QA"), eq("SUCCESS"),
                eq("Published self-service product"), argThat(metadata ->
                        metadata.contains("\"productType\":\"DATASCOPE\"")
                                && metadata.contains("\"approvalMode\":\"REQUIRED\"")));
        verify(audit).record(eq("tester"), eq("SELF_SERVICE_ORDER_REQUESTED"), eq("SELF_SERVICE"),
                eq("self-service-order"), eq(orderId), eq("Customer 360 QA"), eq("SUCCESS"),
                eq("Requested self-service product"), argThat(metadata ->
                        metadata.contains("\"productId\":\"" + productId + "\"")
                                && !metadata.contains("Sprint regression")));
        verify(audit).record(eq("approver"), eq("SELF_SERVICE_ORDER_APPROVED"), eq("SELF_SERVICE"),
                eq("self-service-order"), eq(orderId), eq("Customer 360 QA"), eq("SUCCESS"),
                eq("Approved self-service order"), argThat(metadata ->
                        metadata.contains("\"decision\":\"APPROVED\"")
                                && metadata.contains("\"requester\":\"tester\"")
                                && !metadata.contains("Approved for sprint 52")));
        verify(audit).record(eq("tester"), eq("SELF_SERVICE_ORDER_FULFILLED"), eq("SELF_SERVICE"),
                eq("self-service-order"), eq(orderId), eq("Customer 360 QA"), eq("SUCCESS"),
                eq("Fulfilled self-service order"), argThat(metadata ->
                        metadata.contains("\"runType\":\"DATASCOPE\"")
                                && metadata.contains("\"runRef\":\"91\"")));
    }

    @Test
    void administratorRequestBypassesApprovalWithoutSelfApproval() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:self_service_admin_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        schema(jdbc);
        jdbc.update("INSERT INTO forge_users(id,username) VALUES (20,'admin'),(30,'architect')");
        jdbc.update("INSERT INTO datascope_saved_jobs(id,name,description,owner_user_id,owner_username,self_service_enabled,self_service_label,updated_at) VALUES ('template-1','Customer QA','Masked customer slice',30,'architect',FALSE,NULL,CURRENT_TIMESTAMP)");
        DataScopeJobService jobs = mock(DataScopeJobService.class);
        when(jobs.runSelfService(eq("template-1"), anyMap(), any(ProvisioningService.ApprovalEvidence.class)))
                .thenReturn(Map.of("runId", 92L, "status", "QUEUED"));
        EnterpriseSelfServiceService service = new EnterpriseSelfServiceService(jdbc, new ObjectMapper(),
                jobs, mock(SyntheticGenService.class), mock(MappingExecutionService.class),
                mock(ReservationService.class), mock(VirtualizationService.class),
                mock(IntegrationWebhookService.class), mock(AuditService.class));
        AccessPrincipal architect = new AccessPrincipal(30L, "architect", "Architect", Set.of("TDM_ARCHITECT"),
                Set.of("datascope.manage", "provision.read", "provision.run"));
        AccessPrincipal admin = new AccessPrincipal(20L, "admin", "Administrator", Set.of("ADMIN"),
                Set.of("admin.all", "provision.approve", "provision.read", "provision.run"));

        Map<String, Object> product = AccessContext.callAs(architect, null, () -> service.publish(
                new EnterpriseSelfServiceService.ProductRequest("DATASCOPE", "template-1", 1, "Customer 360 QA",
                        "Approved masked customer data", "Customer", "regression,masked", true, "REQUIRED",
                        new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(),
                        List.of("QA"), "Use in QA.")));
        Map<String, Object> order = AccessContext.callAs(admin, null, () -> service.request(
                new EnterpriseSelfServiceService.OrderRequest(String.valueOf(product.get("id")),
                        "Administrator regression request", "REGRESSION", "QA", Map.of(), 100L,
                        null, "DATABASE", false, null, null)));

        assertEquals("APPROVED", order.get("status"));
        List<?> events = (List<?>) order.get("events");
        assertEquals(2, events.size());
        assertEquals("REQUESTED", ((Map<?, ?>) events.get(0)).get("eventType"));
        assertEquals("APPROVED", ((Map<?, ?>) events.get(1)).get("eventType"));
        assertEquals("admin", order.get("decisionBy"));

        String orderId = String.valueOf(order.get("id"));
        Map<String, Object> fulfilled = AccessContext.callAs(admin, null, () -> service.fulfill(orderId));
        assertEquals("SUBMITTED", fulfilled.get("status"));
        verify(jobs).runSelfService(eq("template-1"), anyMap(), argThat(evidence ->
                "SELF_SERVICE_ORDER".equals(evidence.source())
                        && orderId.equals(evidence.reference())
                        && "admin".equals(evidence.approvedBy())));
    }

    @Test
    void preApprovedCatalogPolicyCarriesItsDecisionIntoProvisioning() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:self_service_policy_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        schema(jdbc);
        jdbc.update("INSERT INTO forge_users(id,username) VALUES (10,'tester'),(30,'architect')");
        jdbc.update("INSERT INTO datascope_saved_jobs(id,name,description,owner_user_id,owner_username,self_service_enabled,self_service_label,updated_at) VALUES ('template-1','Customer QA','Masked customer slice',30,'architect',FALSE,NULL,CURRENT_TIMESTAMP)");
        DataScopeJobService jobs = mock(DataScopeJobService.class);
        when(jobs.runSelfService(eq("template-1"), anyMap(), any(ProvisioningService.ApprovalEvidence.class)))
                .thenReturn(Map.of("runId", 93L, "status", "QUEUED"));
        EnterpriseSelfServiceService service = new EnterpriseSelfServiceService(jdbc, new ObjectMapper(), jobs,
                mock(SyntheticGenService.class), mock(MappingExecutionService.class), mock(ReservationService.class),
                mock(VirtualizationService.class), mock(IntegrationWebhookService.class), mock(AuditService.class));
        AccessPrincipal architect = new AccessPrincipal(30L, "architect", "Architect", Set.of("TDM_ARCHITECT"),
                Set.of("datascope.manage", "provision.read", "provision.run"));
        AccessPrincipal tester = new AccessPrincipal(10L, "tester", "Tester", Set.of("TESTER"),
                Set.of("provision.read", "provision.run"));

        Map<String, Object> product = AccessContext.callAs(architect, null, () -> service.publish(
                new EnterpriseSelfServiceService.ProductRequest("DATASCOPE", "template-1", 1, "Instant Customer QA",
                        "Pre-approved customer regression data", "Customer", "instant,masked", true, "NONE",
                        new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(),
                        List.of("QA"), "Use in QA.")));
        Map<String, Object> order = AccessContext.callAs(tester, null, () -> service.request(
                new EnterpriseSelfServiceService.OrderRequest(String.valueOf(product.get("id")),
                        "Customer regression coverage", "REGRESSION", "QA", Map.of(), 25L,
                        null, "DATABASE", false, null, null)));

        assertEquals("APPROVED", order.get("status"));
        assertEquals("PRODUCT_POLICY", order.get("decisionBy"));
        String orderId = String.valueOf(order.get("id"));
        AccessContext.callAs(tester, null, () -> service.fulfill(orderId));
        verify(jobs).runSelfService(eq("template-1"), anyMap(), argThat(evidence ->
                orderId.equals(evidence.reference())
                        && "PRODUCT_POLICY".equals(evidence.approvedBy())
                        && evidence.basis().contains("catalog product")));
    }

    @Test
    void fixedSyntheticProductRejectsUnsupportedVolumeOverride() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:self_service_contract_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        schema(jdbc);
        jdbc.update("INSERT INTO forge_users(id,username) VALUES (10,'tester'),(30,'architect')");
        jdbc.update("INSERT INTO synthetic_saved_jobs(id,name,description,approval_status,updated_at) VALUES ('synthetic-1','Payments regression','Approved receiver plan','APPROVED',CURRENT_TIMESTAMP)");
        EnterpriseSelfServiceService service = new EnterpriseSelfServiceService(jdbc, new ObjectMapper(),
                mock(DataScopeJobService.class), mock(SyntheticGenService.class), mock(MappingExecutionService.class),
                mock(ReservationService.class), mock(VirtualizationService.class),
                mock(IntegrationWebhookService.class), mock(AuditService.class));
        AccessPrincipal architect = new AccessPrincipal(30L, "architect", "Architect", Set.of("TDM_ARCHITECT"),
                Set.of("datascope.manage", "provision.read", "provision.run"));
        AccessPrincipal tester = new AccessPrincipal(10L, "tester", "Tester", Set.of("TESTER"),
                Set.of("provision.read", "provision.run"));
        Map<String, Object> product = AccessContext.callAs(architect, null, () -> service.publish(
                new EnterpriseSelfServiceService.ProductRequest("SYNTHETIC", "synthetic-1", 1, "Payments regression",
                        "Fixed approved synthetic receiver plan", "Payments", "synthetic", true, "NONE",
                        new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(),
                        List.of("QA"), "Use the approved design.")));

        ApiException error = assertThrows(ApiException.class, () -> AccessContext.callAs(tester, null, () -> service.request(
                new EnterpriseSelfServiceService.OrderRequest(String.valueOf(product.get("id")),
                        "Payments regression coverage", "REGRESSION", "QA", Map.of(), 100L,
                        null, "DATABASE", false, null, null))));
        assertEquals("Requested volume is fixed by this product and cannot be changed", error.getMessage());
    }

    @Test
    void cancelCommentAndRunnerExportUseStructuredAuditWithoutLeakingFreeText() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:self_service_ops_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""));
        schema(jdbc);
        jdbc.update("INSERT INTO forge_users(id,username) VALUES (10,'tester'),(30,'architect')");
        jdbc.update("INSERT INTO datascope_saved_jobs(id,name,description,owner_user_id,owner_username,self_service_enabled,self_service_label,updated_at) VALUES ('template-1','Customer QA','Masked customer slice',30,'architect',FALSE,NULL,CURRENT_TIMESTAMP)");
        AuditService audit = mock(AuditService.class);
        EnterpriseSelfServiceService service = new EnterpriseSelfServiceService(jdbc, new ObjectMapper(),
                mock(DataScopeJobService.class), mock(SyntheticGenService.class), mock(MappingExecutionService.class),
                mock(ReservationService.class), mock(VirtualizationService.class),
                mock(IntegrationWebhookService.class), audit);
        AccessPrincipal architect = new AccessPrincipal(30L, "architect", "Architect", Set.of("TDM_ARCHITECT"),
                Set.of("datascope.manage", "provision.read", "provision.run"));
        AccessPrincipal tester = new AccessPrincipal(10L, "tester", "Tester", Set.of("TESTER"),
                Set.of("provision.read", "provision.run"));

        Map<String, Object> product = AccessContext.callAs(architect, null, () -> service.publish(
                new EnterpriseSelfServiceService.ProductRequest("DATASCOPE", "template-1", 1, "Customer 360 QA",
                        "Approved masked customer data", "Customer", "regression,masked", true, "REQUIRED",
                        new ObjectMapper().createObjectNode(), new ObjectMapper().createObjectNode(),
                        List.of("QA"), "Use in QA.")));
        Map<String, Object> order = AccessContext.callAs(tester, null, () -> service.request(
                new EnterpriseSelfServiceService.OrderRequest(String.valueOf(product.get("id")),
                        "Need masked regression customers", "REGRESSION", "QA", Map.of(), 100L,
                        null, "DATABASE", false, null, null)));
        String orderId = String.valueOf(order.get("id"));
        AccessContext.callAs(tester, null, () -> service.comment(orderId, new EnterpriseSelfServiceService.Comment("Please prioritize this request")));
        AccessContext.callAs(tester, null, () -> service.runner(orderId));
        AccessContext.callAs(tester, null, () -> service.cancel(orderId, new EnterpriseSelfServiceService.Comment("No longer needed for the release")));

        verify(audit).record(eq("tester"), eq("SELF_SERVICE_ORDER_COMMENTED"), eq("SELF_SERVICE"),
                eq("self-service-order"), eq(orderId), eq("Customer 360 QA"), eq("SUCCESS"),
                eq("Commented on self-service order"), argThat(metadata ->
                        metadata.contains("\"messageLength\":30")
                                && !metadata.contains("Please prioritize this request")));
        verify(audit).record(eq("tester"), eq("SELF_SERVICE_ORDER_RUNNER_EXPORTED"), eq("SELF_SERVICE"),
                eq("self-service-order"), eq(orderId), eq("Customer 360 QA"), eq("SUCCESS"),
                eq("Exported self-service runner commands"), argThat(metadata ->
                        metadata.contains("\"productType\":\"DATASCOPE\"")
                                && !metadata.contains("FORGETDM_TOKEN")
                                && !metadata.contains("curl")));
        verify(audit).record(eq("tester"), eq("SELF_SERVICE_ORDER_CANCELED"), eq("SELF_SERVICE"),
                eq("self-service-order"), eq(orderId), eq("Customer 360 QA"), eq("SUCCESS"),
                eq("Canceled self-service order"), argThat(metadata ->
                        metadata.contains("\"previousStatus\":\"PENDING_APPROVAL\"")
                                && metadata.contains("\"reasonLength\":32")
                                && !metadata.contains("No longer needed for the release")));
    }

    private static void schema(JdbcTemplate jdbc) {
        jdbc.execute("CREATE TABLE forge_users(id BIGINT PRIMARY KEY,username VARCHAR(120))");
        jdbc.execute("CREATE TABLE datascope_saved_jobs(id VARCHAR(36) PRIMARY KEY,name VARCHAR(200),description VARCHAR(2000),owner_user_id BIGINT,owner_username VARCHAR(120),self_service_enabled BOOLEAN,self_service_label VARCHAR(200),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE self_service_products(id VARCHAR(36) PRIMARY KEY,product_type VARCHAR(40),artifact_id VARCHAR(120),artifact_version INTEGER,label VARCHAR(200),description VARCHAR(2000),category VARCHAR(100),tags VARCHAR(1000),owner_user_id BIGINT,owner_username VARCHAR(120),enabled BOOLEAN,approval_mode VARCHAR(30),questionnaire_json TEXT,guardrails_json TEXT,allowed_environments VARCHAR(1000),delivery_instructions TEXT,created_at TIMESTAMP,updated_at TIMESTAMP,UNIQUE(product_type,artifact_id))");
        jdbc.execute("CREATE TABLE self_service_orders(id VARCHAR(36) PRIMARY KEY,product_id VARCHAR(36),product_type VARCHAR(40),artifact_id VARCHAR(120),product_label VARCHAR(200),requested_by_id BIGINT,requested_by VARCHAR(120),purpose VARCHAR(1000),test_type VARCHAR(80),environment VARCHAR(80),parameters_json TEXT,requested_volume BIGINT,requested_variety VARCHAR(1000),delivery_mode VARCHAR(40),reservation_requested BOOLEAN,reservation_hours INTEGER,schedule_at TIMESTAMP,status VARCHAR(40),decision_by_id BIGINT,decision_by VARCHAR(120),decision_note VARCHAR(1000),decided_at TIMESTAMP,run_type VARCHAR(40),run_ref VARCHAR(120),result_json TEXT,fulfilled_at TIMESTAMP,canceled_at TIMESTAMP,expires_at TIMESTAMP,created_at TIMESTAMP,updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE self_service_order_events(id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,order_id VARCHAR(36),event_type VARCHAR(60),actor VARCHAR(120),message VARCHAR(2000),detail_json TEXT,created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE synthetic_saved_jobs(id VARCHAR(36),name VARCHAR(200),description VARCHAR(500),approval_status VARCHAR(40),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE mapping_definitions(id BIGINT,name VARCHAR(200),description VARCHAR(500),updated_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE virtual_snapshots(id BIGINT,name VARCHAR(200),note VARCHAR(500),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE virtual_databases(id BIGINT,name VARCHAR(200),created_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE data_sources(id BIGINT)");
    }
}
