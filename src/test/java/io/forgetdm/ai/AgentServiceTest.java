package io.forgetdm.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AgentServiceTest {

    @Test
    void planCreatorCannotSelfApproveEvenWithApprovalPermission() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        ObjectMapper json = new ObjectMapper();
        var run = new AgentRunRepository.StoredRun(1, "story", "AWAITING_PLAN_APPROVAL", "summary", "ollama", "model",
                json.createObjectNode(), json.createArrayNode(), json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode(), .8, "MEDIUM", "fingerprint", true, "maker", null, null, null,
                Instant.now(), Instant.now(), List.of());
        when(repository.get(1)).thenReturn(run);
        AgentService service = new AgentService(mock(AgentPlanningService.class), repository,
                mock(ForgeIntelligenceStoreService.class), mock(AiTools.class), mock(AuditService.class), json);
        AccessPrincipal maker = new AccessPrincipal(10L, "maker", "Maker", Set.of("TDM_ARCHITECT"),
                Set.of("assistant.use", "provision.approve"));

        assertThrows(ApiException.class, () -> AccessContext.callAs(maker, null, () -> service.approvePlan(1)));
        verify(repository, never()).approvePlan(anyLong(), anyString());
    }

    @Test
    void rejectingPendingActionWritesStructuredAudit() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AuditService audit = mock(AuditService.class);
        ObjectMapper json = new ObjectMapper();
        var step = new AgentRunRepository.StoredStep(77, 2, 3, "RUN", "Launch provision",
                "execute approved load", "EXECUTE", "AWAITING_APPROVAL", true, true,
                "provision.launch", json.createObjectNode().put("jobId", 99), "Launch job",
                json.createArrayNode(), null, null, null);
        var run = new AgentRunRepository.StoredRun(2, "story", "AWAITING_ACTION_APPROVAL", "summary", "ollama", "model",
                json.createObjectNode(), json.createArrayNode(), json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode(), .8, "MEDIUM", "fingerprint", true, "maker", "checker", Instant.now(), null,
                Instant.now(), Instant.now(), List.of(step));
        var done = new AgentRunRepository.StoredRun(2, "story", "DONE", "summary", "ollama", "model",
                json.createObjectNode(), json.createArrayNode(), json.createArrayNode(), json.createArrayNode(),
                json.createArrayNode(), .8, "MEDIUM", "fingerprint", true, "maker", "checker", Instant.now(), null,
                Instant.now(), Instant.now(), List.of());
        when(repository.get(2)).thenReturn(run, done, done);
        AgentService service = new AgentService(mock(AgentPlanningService.class), repository,
                mock(ForgeIntelligenceStoreService.class), mock(AiTools.class), audit, json);
        AccessPrincipal checker = new AccessPrincipal(11L, "checker", "Checker", Set.of("TDM_ARCHITECT"),
                Set.of("assistant.use", "provision.approve"));

        AccessContext.callAs(checker, null, () -> service.reject(2));

        verify(repository).setStepStatus(eq(77L), eq("SKIPPED"), any(), eq(false), eq(true));
        verify(audit).record(eq("checker"), eq("AGENT_ACTION_REJECTED"), eq("AI"), eq("AGENT_RUN"),
                eq("2"), eq("Agent run 2"), eq("SUCCESS"), eq("Rejected pending agent action"),
                argThat(metadata -> metadata.contains("\"stepId\":77")
                        && metadata.contains("\"stepOrdinal\":3")
                        && metadata.contains("\"actionName\":\"provision.launch\"")
                        && !metadata.contains("\"jobId\":99")));
    }

    @Test
    void compileAndApprovePlanRecordStructuredMakerCheckerEvidenceWithoutStoryContent() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentPlanningService planning = mock(AgentPlanningService.class);
        ForgeIntelligenceStoreService store = mock(ForgeIntelligenceStoreService.class);
        AuditService audit = mock(AuditService.class);
        ObjectMapper json = new ObjectMapper();
        AgentContracts.Compilation compilation = mock(AgentContracts.Compilation.class);
        when(planning.compile(anyString(), any(), any(), eq(false))).thenReturn(compilation);
        when(repository.create(anyString(), eq(compilation), eq("maker"))).thenReturn(10L);
        var awaiting = run(json, 10, "AWAITING_PLAN_APPROVAL", "maker", null, List.of());
        var approved = run(json, 10, "APPROVED", "maker", "checker", List.of());
        when(repository.get(10)).thenReturn(awaiting, awaiting, approved);
        AgentService service = new AgentService(planning, repository, store, mock(AiTools.class), audit, json);

        AccessContext.callAs(principal("maker"), null,
                () -> service.plan("SECRET CUSTOMER STORY", "ollama", "private-model"));
        AccessContext.callAs(principal("checker"), null, () -> service.approvePlan(10));

        verify(audit).record(eq("maker"), eq("AGENT_PLAN_COMPILED"), eq("AI"), eq("AGENT_RUN"),
                eq("10"), eq("Agent run 10"), eq("SUCCESS"), eq("Compiled grounded agent plan"),
                safeMetadata("SECRET CUSTOMER STORY", "SECRET RUN SUMMARY"));
        verify(audit).record(eq("checker"), eq("AGENT_PLAN_APPROVED"), eq("AI"), eq("AGENT_RUN"),
                eq("10"), eq("Agent run 10"), eq("SUCCESS"), eq("Approved grounded agent plan"),
                argThat(metadata -> metadata.contains("\"maker\":\"maker\"")
                        && metadata.contains("\"checker\":\"checker\"")
                        && metadata.contains("\"decision\":\"APPROVED\"")
                        && !metadata.contains("SECRET CUSTOMER STORY")
                        && !metadata.contains("SECRET RUN SUMMARY")));
    }

    @Test
    void nextStepAuditsApprovalGateAndMissingActionFailureWithoutActionArguments() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AuditService audit = mock(AuditService.class);
        ObjectMapper json = new ObjectMapper();
        var gated = step(json, 101, 21, 1, "PENDING", "provision.launch", true, true);
        var gatedRun = run(json, 21, "APPROVED", "maker", "checker", List.of(gated));
        var waitingRun = run(json, 21, "AWAITING_ACTION_APPROVAL", "maker", "checker", List.of(gated));
        when(repository.get(21)).thenReturn(gatedRun, waitingRun);
        AgentService service = service(repository, mock(AiTools.class), audit, json);

        AccessContext.callAs(principal("operator"), null, () -> service.runNext(21));

        verify(audit).record(eq("operator"), eq("AGENT_ACTION_APPROVAL_REQUIRED"), eq("AI"), eq("AGENT_RUN"),
                eq("21"), eq("Agent run 21"), eq("SUCCESS"), eq("Agent action paused for explicit approval"),
                safeMetadata("SECRET ACTION ARGUMENT"));

        reset(repository, audit);
        var missing = step(json, 102, 22, 1, "PENDING", null, true, true);
        var missingRun = run(json, 22, "APPROVED", "maker", "checker", List.of(missing));
        var failedRun = run(json, 22, "FAILED", "maker", "checker", List.of(missing));
        when(repository.get(22)).thenReturn(missingRun, failedRun);
        AgentService missingService = service(repository, mock(AiTools.class), audit, json);

        AccessContext.callAs(principal("operator"), null, () -> missingService.runNext(22));

        verify(audit).record(eq("operator"), eq("AGENT_ACTION_FAILED"), eq("AI"), eq("AGENT_RUN"),
                eq("22"), eq("Agent run 22"), eq("FAILURE"),
                eq("Agent step has no compiled governed action"),
                argThat(metadata -> metadata.contains("\"errorType\":\"MissingCompiledAction\"")
                        && !metadata.contains("SECRET ACTION ARGUMENT")));
    }

    @Test
    void approvedActionAuditsSuccessAndSanitizedFailure() {
        ObjectMapper json = new ObjectMapper();
        AuditService successAudit = mock(AuditService.class);
        AgentRunRepository successRepo = mock(AgentRunRepository.class);
        AiTools successTools = mock(AiTools.class);
        var pending = step(json, 201, 31, 2, "AWAITING_APPROVAL", "provision.launch", true, true);
        var successRun = run(json, 31, "AWAITING_ACTION_APPROVAL", "maker", "checker", List.of(pending));
        var completed = run(json, 31, "DONE", "maker", "checker", List.of(
                step(json, 201, 31, 2, "DONE", "provision.launch", true, true)));
        when(successRepo.get(31)).thenReturn(successRun, completed, completed, completed);
        when(successTools.exists("provision.launch")).thenReturn(true);
        when(successTools.requiresConfirmation("provision.launch")).thenReturn(true);
        when(successTools.execute(eq("provision.launch"), any())).thenReturn(
                "{\"ok\":true,\"secretResult\":\"DO_NOT_AUDIT_TOOL_RESULT\"}");
        AgentService successService = service(successRepo, successTools, successAudit, json);

        AccessContext.callAs(principal("checker"), null, () -> successService.approve(31));

        verify(successAudit).record(eq("checker"), eq("AGENT_ACTION_EXECUTED"), eq("AI"), eq("AGENT_RUN"),
                eq("31"), eq("Agent run 31"), eq("SUCCESS"), eq("Executed approved governed agent action"),
                safeMetadata("SECRET ACTION ARGUMENT", "DO_NOT_AUDIT_TOOL_RESULT"));

        AuditService failureAudit = mock(AuditService.class);
        AgentRunRepository failureRepo = mock(AgentRunRepository.class);
        AiTools failureTools = mock(AiTools.class);
        var failureRun = run(json, 32, "AWAITING_ACTION_APPROVAL", "maker", "checker", List.of(
                step(json, 202, 32, 2, "AWAITING_APPROVAL", "provision.launch", true, true)));
        when(failureRepo.get(32)).thenReturn(failureRun, run(json, 32, "FAILED", "maker", "checker", List.of()));
        when(failureTools.exists("provision.launch")).thenReturn(true);
        when(failureTools.requiresConfirmation("provision.launch")).thenReturn(true);
        when(failureTools.execute(eq("provision.launch"), any()))
                .thenThrow(new IllegalStateException("SECRET DATABASE ERROR"));
        AgentService failureService = service(failureRepo, failureTools, failureAudit, json);

        AccessContext.callAs(principal("checker"), null, () -> failureService.approve(32));

        verify(failureAudit).record(eq("checker"), eq("AGENT_ACTION_FAILED"), eq("AI"), eq("AGENT_RUN"),
                eq("32"), eq("Agent run 32"), eq("FAILURE"), eq("Approved governed agent action failed"),
                argThat(metadata -> metadata.contains("\"errorType\":\"IllegalStateException\"")
                        && !metadata.contains("SECRET DATABASE ERROR")
                        && !metadata.contains("SECRET ACTION ARGUMENT")));
    }

    @Test
    void cancelFeedbackAndRevisionAuditOnlySafeDecisionMetadata() {
        ObjectMapper json = new ObjectMapper();
        AuditService audit = mock(AuditService.class);
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AgentPlanningService planning = mock(AgentPlanningService.class);
        var active = run(json, 41, "RUNNING", "maker", "checker", List.of());
        var cancelled = run(json, 41, "CANCELED", "maker", "checker", List.of());
        when(repository.get(41)).thenReturn(active, cancelled, active);
        AgentService service = new AgentService(planning, repository,
                mock(ForgeIntelligenceStoreService.class), mock(AiTools.class), audit, json);

        AccessContext.callAs(principal("operator"), null, () -> service.cancel(41));
        AccessContext.callAs(principal("operator"), null, () -> service.feedback(41, 4, true,
                json.createObjectNode().put("correction", "SECRET CORRECTION"),
                "SECRET FEEDBACK COMMENT"));

        verify(audit).record(eq("operator"), eq("AGENT_RUN_CANCELED"), eq("AI"), eq("AGENT_RUN"),
                eq("41"), eq("Agent run 41"), eq("SUCCESS"), eq("Canceled agent run"),
                safeMetadata("SECRET CUSTOMER STORY", "SECRET RUN SUMMARY"));
        verify(audit).record(eq("operator"), eq("AGENT_PLAN_FEEDBACK"), eq("AI"), eq("AGENT_RUN"),
                eq("41"), eq("Agent run 41"), eq("SUCCESS"), eq("Recorded agent-plan feedback"),
                argThat(metadata -> metadata.contains("\"rating\":4")
                        && metadata.contains("\"accepted\":true")
                        && metadata.contains("\"correctionProvided\":true")
                        && metadata.contains("\"commentLength\":23")
                        && !metadata.contains("SECRET CORRECTION")
                        && !metadata.contains("SECRET FEEDBACK COMMENT")));

        reset(repository, audit);
        var previous = run(json, 42, "BLOCKED", "maker", null, List.of());
        var revised = run(json, 43, "AWAITING_PLAN_APPROVAL", "operator", null, List.of());
        AgentContracts.Compilation compilation = mock(AgentContracts.Compilation.class);
        when(planning.compile(anyString(), any(), any(), eq(false))).thenReturn(compilation);
        when(repository.get(42)).thenReturn(previous);
        when(repository.create(anyString(), eq(compilation), eq("operator"))).thenReturn(43L);
        when(repository.get(43)).thenReturn(revised);
        AgentService revisionService = new AgentService(planning, repository,
                mock(ForgeIntelligenceStoreService.class), mock(AiTools.class), audit, json);

        Map<String, Object> result = AccessContext.callAs(principal("operator"), null,
                () -> revisionService.revise(42,
                        json.createObjectNode().put("account", "SECRET CLARIFICATION"),
                        "ollama", "private-model"));
        assertEquals(43L, result.get("id"));

        verify(audit).record(eq("operator"), eq("AGENT_PLAN_SUPERSEDED"), eq("AI"), eq("AGENT_RUN"),
                eq("42"), eq("Agent run 42"), eq("SUCCESS"), eq("Superseded agent plan with a revision"),
                argThat(metadata -> metadata.contains("\"newRunId\":43")
                        && metadata.contains("\"answerCount\":1")
                        && !metadata.contains("SECRET CLARIFICATION")
                        && !metadata.contains("SECRET CUSTOMER STORY")));
    }

    @Test
    void completionAuditIsStructuredAndContainsOnlyCounts() {
        AgentRunRepository repository = mock(AgentRunRepository.class);
        AuditService audit = mock(AuditService.class);
        ObjectMapper json = new ObjectMapper();
        var noPending = run(json, 51, "APPROVED", "maker", "checker", List.of(
                step(json, 501, 51, 1, "DONE", null, false, false)));
        var completed = run(json, 51, "DONE", "maker", "checker", List.of(
                step(json, 501, 51, 1, "DONE", null, false, false)));
        when(repository.get(51)).thenReturn(noPending, completed, completed);
        AgentService service = service(repository, mock(AiTools.class), audit, json);

        AccessContext.callAs(principal("operator"), null, () -> service.runNext(51));

        verify(audit).record(eq("operator"), eq("AGENT_RUN_COMPLETED"), eq("AI"), eq("AGENT_RUN"),
                eq("51"), eq("Agent run 51"), eq("SUCCESS"), eq("Completed all approved agent-plan steps"),
                argThat(metadata -> metadata.contains("\"completedStepCount\":1")
                        && !metadata.contains("SECRET CUSTOMER STORY")
                        && !metadata.contains("SECRET RUN SUMMARY")));
    }

    private static AgentService service(AgentRunRepository repository, AiTools tools,
                                        AuditService audit, ObjectMapper json) {
        return new AgentService(mock(AgentPlanningService.class), repository,
                mock(ForgeIntelligenceStoreService.class), tools, audit, json);
    }

    private static AccessPrincipal principal(String username) {
        return new AccessPrincipal(99L, username, username, Set.of("TDM_ARCHITECT"),
                Set.of("assistant.use", "assistant.manage", "provision.approve"));
    }

    private static AgentRunRepository.StoredRun run(ObjectMapper json, long id, String status,
                                                    String createdBy, String approvedBy,
                                                    List<AgentRunRepository.StoredStep> steps) {
        return new AgentRunRepository.StoredRun(id, "SECRET CUSTOMER STORY", status, "SECRET RUN SUMMARY",
                "ollama", "private-model", json.createObjectNode(), json.createArrayNode(),
                json.createArrayNode(), json.createArrayNode(), json.createArrayNode(), .91, "MEDIUM",
                "0123456789abcdef", true, createdBy, approvedBy,
                approvedBy == null ? null : Instant.now(), null, Instant.now(), Instant.now(), steps);
    }

    private static AgentRunRepository.StoredStep step(ObjectMapper json, long id, long runId, int ordinal,
                                                       String status, String actionName,
                                                       boolean changesData, boolean requiresApproval) {
        return new AgentRunRepository.StoredStep(id, runId, ordinal, "STEP", "SECRET STEP TITLE",
                "SECRET STEP DETAIL", changesData ? "EXECUTE" : "GROUND", status, changesData,
                requiresApproval, actionName,
                json.createObjectNode().put("secretArg", "SECRET ACTION ARGUMENT"), "SECRET ACTION SUMMARY",
                json.createArrayNode(), null, null, null);
    }

    private static String safeMetadata(String... forbidden) {
        return argThat(metadata -> {
            if (metadata == null || !metadata.contains("\"fingerprint\":\"0123456789abcdef\"")) return false;
            for (String value : forbidden) {
                if (metadata.contains(value)) return false;
            }
            return true;
        });
    }
}
