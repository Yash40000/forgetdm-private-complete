package io.forgetdm.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.ai.AgentRunRepository.StoredRun;
import io.forgetdm.ai.AgentRunRepository.StoredStep;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Durable, grounded plan-and-execute agent. The model extracts intent; ForgeTDM owns every action. */
@Service
public class AgentService {
    private final AgentPlanningService planning;
    private final AgentRunRepository runs;
    private final ForgeIntelligenceStoreService store;
    private final AiTools tools;
    private final AuditService audit;
    private final ObjectMapper json;

    public AgentService(AgentPlanningService planning, AgentRunRepository runs,
                        ForgeIntelligenceStoreService store, AiTools tools,
                        AuditService audit, ObjectMapper json) {
        this.planning = planning;
        this.runs = runs;
        this.store = store;
        this.tools = tools;
        this.audit = audit;
        this.json = json;
    }

    public Map<String, Object> plan(String goal, String provider, String model) {
        return plan(goal, provider, model, false);
    }

    public Map<String, Object> plan(String goal, String provider, String model, boolean refreshStore) {
        if (goal == null || goal.isBlank()) throw ApiException.bad("Describe the user story or test case");
        if (refreshStore && AccessContext.current().map(principal -> !principal.hasPermission("assistant.manage")).orElse(false))
            throw ApiException.forbidden("Only TDM architects can force a Forge Data Store synchronization");
        AgentContracts.Compilation compilation = planning.compile(goal.trim(), provider, model, refreshStore);
        long id = runs.create(goal.trim(), compilation, actor());
        StoredRun created = runs.get(id);
        LinkedHashMap<String, Object> metadata = runMetadata(created);
        metadata.put("refreshStore", refreshStore);
        auditRun(created, "AGENT_PLAN_COMPILED", "SUCCESS", "Compiled grounded agent plan", metadata);
        return view(created);
    }

    public Map<String, Object> approvePlan(long id) {
        StoredRun run = runs.get(id);
        if (hasBlockers(run.validation())) throw ApiException.bad("Resolve all blocking questions before approving this plan");
        if (run.createdBy().equalsIgnoreCase(actor()))
            throw ApiException.bad("Maker-checker control: the plan creator cannot approve their own plan");
        runs.approvePlan(id, actor());
        StoredRun approved = runs.get(id);
        store.recordApprovedPlan(id, approved.goal(), approved.summary(), approved.intent(), approved.plan(),
                approved.fingerprint(), actor());
        LinkedHashMap<String, Object> metadata = runMetadata(approved);
        metadata.put("maker", approved.createdBy());
        metadata.put("checker", actor());
        metadata.put("decision", "APPROVED");
        auditRun(approved, "AGENT_PLAN_APPROVED", "SUCCESS", "Approved grounded agent plan", metadata);
        return view(approved);
    }

    public Map<String, Object> runNext(long id) {
        StoredRun run = runs.get(id);
        if ("AWAITING_ACTION_APPROVAL".equals(run.status())) throw ApiException.bad("An action is awaiting approval");
        if (terminal(run.status())) throw ApiException.bad("This plan is " + run.status().toLowerCase());
        if (!List.of("APPROVED", "RUNNING").contains(run.status()))
            throw ApiException.bad("Approve the grounded plan before execution");

        StoredStep step = run.steps().stream().filter(value -> "PENDING".equals(value.status())).findFirst().orElse(null);
        if (step == null) {
            finish(run.id());
            return view(runs.get(id));
        }
        if (step.requiresApproval() || step.changesData()) {
            if (step.actionName() == null || step.actionName().isBlank()) {
                ObjectNode result = json.createObjectNode().put("message", "No executable action was compiled for this step");
                runs.setStepStatus(step.id(), "FAILED", result, true, true);
                runs.setRunStatus(id, "FAILED");
                runs.event(id, "STEP_FAILED", actor(), step.title(), result);
                recordStepEvent(run, step, "AGENT_ACTION_FAILED", "FAILURE",
                        "Agent step has no compiled governed action",
                        Map.of("errorType", "MissingCompiledAction", "newStatus", "FAILED"));
            } else {
                runs.setStepStatus(step.id(), "AWAITING_APPROVAL", null, false, false);
                runs.setRunStatus(id, "AWAITING_ACTION_APPROVAL");
                runs.event(id, "ACTION_APPROVAL_REQUIRED", actor(), step.title(),
                        json.createObjectNode().put("action", step.actionName()).put("step", step.ordinal()));
                recordStepEvent(run, step, "AGENT_ACTION_APPROVAL_REQUIRED", "SUCCESS",
                        "Agent action paused for explicit approval",
                        Map.of("previousStatus", step.status(), "newStatus", "AWAITING_APPROVAL"));
            }
            return view(runs.get(id));
        }

        runs.setStepStatus(step.id(), "RUNNING", null, true, false);
        ObjectNode result = json.createObjectNode();
        result.put("message", safeStepResult(step));
        result.set("evidence", step.evidence());
        runs.setStepStatus(step.id(), "DONE", result, true, true);
        runs.event(id, "SAFE_STEP_COMPLETED", actor(), step.title(), result);
        recordStepEvent(run, step, "AGENT_SAFE_STEP_COMPLETED", "SUCCESS",
                "Completed non-mutating grounded agent step",
                Map.of("previousStatus", step.status(), "newStatus", "DONE"));
        advance(id);
        return view(runs.get(id));
    }

    public Map<String, Object> runUntilGate(long id) {
        Map<String, Object> view = view(runs.get(id));
        for (int i = 0; i < 30; i++) {
            String status = String.valueOf(view.get("status"));
            if (terminal(status) || "AWAITING_ACTION_APPROVAL".equals(status)) return view;
            view = runNext(id);
        }
        throw ApiException.bad("Plan exceeded the safe step limit");
    }

    public Map<String, Object> approve(long id) {
        StoredRun run = runs.get(id);
        StoredStep step = run.steps().stream().filter(value -> "AWAITING_APPROVAL".equals(value.status())).findFirst()
                .orElseThrow(() -> ApiException.bad("No action is awaiting approval"));
        if (!tools.exists(step.actionName()) || !tools.requiresConfirmation(step.actionName()))
            throw ApiException.bad("Compiled action is not in the governed action registry");

        runs.setStepStatus(step.id(), "RUNNING", null, true, false);
        runs.setRunStatus(id, "RUNNING");
        try {
            String raw = tools.execute(step.actionName(), step.actionArgs());
            JsonNode result = parseResult(raw);
            if (result.hasNonNull("error")) throw new IllegalStateException(result.path("error").asText() + ": " + result.path("detail").asText(""));
            runs.setStepStatus(step.id(), "DONE", result, true, true);
            runs.event(id, "ACTION_EXECUTED", actor(), step.title(),
                    json.createObjectNode().put("action", step.actionName()).set("result", result));
            recordStepEvent(run, step, "AGENT_ACTION_EXECUTED", "SUCCESS",
                    "Executed approved governed agent action",
                    Map.of("previousStatus", step.status(), "newStatus", "DONE"));
            advance(id);
        } catch (Exception e) {
            ObjectNode result = json.createObjectNode().put("error", e.getMessage());
            runs.setStepStatus(step.id(), "FAILED", result, true, true);
            runs.setRunStatus(id, "FAILED");
            runs.event(id, "ACTION_FAILED", actor(), step.title(), result);
            recordStepEvent(run, step, "AGENT_ACTION_FAILED", "FAILURE",
                    "Approved governed agent action failed",
                    Map.of("errorType", e.getClass().getSimpleName(), "newStatus", "FAILED"));
        }
        return view(runs.get(id));
    }

    public Map<String, Object> reject(long id) {
        StoredRun run = runs.get(id);
        StoredStep step = run.steps().stream().filter(value -> "AWAITING_APPROVAL".equals(value.status())).findFirst()
                .orElseThrow(() -> ApiException.bad("No action is awaiting approval"));
        ObjectNode result = json.createObjectNode().put("message", "Skipped by " + actor());
        runs.setStepStatus(step.id(), "SKIPPED", result, false, true);
        runs.event(id, "ACTION_REJECTED", actor(), step.title(), result);
        recordStepEvent(run, step, "AGENT_ACTION_REJECTED", "SUCCESS",
                "Rejected pending agent action",
                Map.of("previousStatus", step.status(), "newStatus", "SKIPPED"));
        advance(id);
        return view(runs.get(id));
    }

    public Map<String, Object> cancel(long id) {
        StoredRun run = runs.get(id);
        if (terminal(run.status())) return view(run);
        runs.cancel(id, actor());
        StoredRun cancelled = runs.get(id);
        LinkedHashMap<String, Object> metadata = runMetadata(cancelled);
        metadata.put("previousStatus", run.status());
        metadata.put("newStatus", "CANCELED");
        auditRun(cancelled, "AGENT_RUN_CANCELED", "SUCCESS", "Canceled agent run", metadata);
        return view(cancelled);
    }

    public Map<String, Object> revise(long id, JsonNode answers, String provider, String model) {
        StoredRun previous = runs.get(id);
        String clarification = answers == null || answers.isNull() ? "" : answers.toPrettyString();
        if (clarification.isBlank()) throw ApiException.bad("Provide answers to the blocking questions");
        runs.setRunStatus(id, "SUPERSEDED");
        Map<String, Object> revised = plan(previous.goal() + "\n\nCLARIFICATIONS:\n" + clarification, provider, model, false);
        runs.event(id, "PLAN_SUPERSEDED", actor(), "A revised plan was compiled", json.valueToTree(Map.of("newRunId", revised.get("id"))));
        LinkedHashMap<String, Object> metadata = runMetadata(previous);
        metadata.put("previousStatus", previous.status());
        metadata.put("newStatus", "SUPERSEDED");
        metadata.put("newRunId", revised.get("id"));
        metadata.put("answerCount", answerCount(answers));
        auditRun(previous, "AGENT_PLAN_SUPERSEDED", "SUCCESS", "Superseded agent plan with a revision", metadata);
        return revised;
    }

    public Map<String, Object> feedback(long id, Integer rating, Boolean accepted, JsonNode correction, String comment) {
        StoredRun run = runs.get(id);
        runs.feedback(id, rating, accepted, correction, comment, actor());
        LinkedHashMap<String, Object> metadata = runMetadata(run);
        if (rating != null) metadata.put("rating", rating);
        if (accepted != null) metadata.put("accepted", accepted);
        metadata.put("correctionProvided", correction != null && !correction.isNull());
        metadata.put("commentLength", comment == null ? 0 : comment.length());
        auditRun(run, "AGENT_PLAN_FEEDBACK", "SUCCESS", "Recorded agent-plan feedback", metadata);
        return Map.of("ok", true, "runId", id);
    }

    public Map<String, Object> get(long id) { return view(runs.get(id)); }
    public List<Map<String, Object>> list() { return runs.list().stream().map(this::view).toList(); }
    public List<Map<String, Object>> events(long id) { return runs.events(id); }

    private void advance(long id) {
        StoredRun updated = runs.get(id);
        boolean failed = updated.steps().stream().anyMatch(step -> "FAILED".equals(step.status()));
        boolean remaining = updated.steps().stream().anyMatch(step -> "PENDING".equals(step.status()));
        if (failed) runs.setRunStatus(id, "FAILED");
        else if (remaining) runs.setRunStatus(id, "APPROVED");
        else finish(id);
    }

    private void finish(long id) {
        runs.setRunStatus(id, "DONE");
        runs.event(id, "RUN_COMPLETED", actor(), "All approved plan steps completed", json.createObjectNode());
        StoredRun completed = runs.get(id);
        LinkedHashMap<String, Object> metadata = runMetadata(completed);
        metadata.put("newStatus", "DONE");
        metadata.put("completedStepCount", completed.steps().stream()
                .filter(step -> List.of("DONE", "SKIPPED").contains(step.status())).count());
        auditRun(completed, "AGENT_RUN_COMPLETED", "SUCCESS", "Completed all approved agent-plan steps", metadata);
    }

    private Map<String, Object> view(StoredRun run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", run.id());
        out.put("goal", run.goal());
        out.put("status", run.status());
        out.put("summary", run.summary());
        out.put("provider", run.providerId());
        out.put("model", run.modelName());
        out.put("intent", run.intent());
        out.put("validation", run.validation());
        out.put("questions", run.questions());
        out.put("evidence", run.evidence());
        out.put("confidence", run.confidence());
        out.put("riskLevel", run.riskLevel());
        out.put("fingerprint", run.fingerprint());
        out.put("modelAssisted", run.modelAssisted());
        out.put("createdBy", run.createdBy());
        out.put("approvedBy", run.approvedBy());
        out.put("approvedAt", run.approvedAt());
        out.put("createdAt", run.createdAt());
        out.put("updatedAt", run.updatedAt());
        boolean independentApprover = AccessContext.current().map(principal ->
                principal.hasPermission("provision.approve") && !run.createdBy().equalsIgnoreCase(principal.username())).orElse(false);
        out.put("canApprovePlan", "AWAITING_PLAN_APPROVAL".equals(run.status()) &&
                !hasBlockers(run.validation()) && independentApprover);
        out.put("approvalMessage", "AWAITING_PLAN_APPROVAL".equals(run.status())
                ? independentApprover ? "You can approve this frozen plan." :
                "Awaiting an independent user with provisioning approval permission; the creator cannot self-approve."
                : null);
        out.put("canExecute", List.of("APPROVED", "RUNNING").contains(run.status()));
        List<Map<String, Object>> steps = new ArrayList<>();
        for (StoredStep step : run.steps()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", step.id());
            item.put("ord", step.ordinal());
            item.put("code", step.code());
            item.put("title", step.title());
            item.put("detail", step.detail());
            item.put("operation", step.operation());
            item.put("status", step.status());
            item.put("changesData", step.changesData());
            item.put("requiresApproval", step.requiresApproval());
            item.put("actionName", step.actionName());
            item.put("actionArgs", step.actionArgs());
            item.put("actionSummary", step.actionSummary());
            item.put("evidence", step.evidence());
            item.put("result", step.result());
            steps.add(item);
        }
        out.put("steps", steps);
        return out;
    }

    private JsonNode parseResult(String raw) {
        try { return raw == null || raw.isBlank() ? json.createObjectNode().put("ok", true) : json.readTree(raw); }
        catch (Exception e) { return json.createObjectNode().put("message", raw); }
    }

    private boolean hasBlockers(JsonNode validation) {
        if (validation == null || !validation.isArray()) return false;
        for (JsonNode issue : validation) if ("BLOCKER".equals(issue.path("severity").asText())) return true;
        return false;
    }

    private String safeStepResult(StoredStep step) {
        return switch (step.operation()) {
            case "GROUND" -> "Intent resolved against versioned Forge Data Store citations.";
            case "PRIVACY" -> "Privacy boundary and policy references verified.";
            case "COMPOSE" -> "Dataset composition requirements frozen into the approved plan.";
            case "VALIDATE" -> "Validation contract attached to execution evidence.";
            default -> "Safe planning step completed.";
        };
    }

    private boolean terminal(String status) {
        return List.of("DONE", "FAILED", "CANCELED", "SUPERSEDED", "BLOCKED").contains(status);
    }

    private String actor() { return AccessContext.current().map(principal -> principal.username()).orElse("system"); }

    private void recordStepEvent(StoredRun run, StoredStep step, String action, String outcome,
                                 String detail, Map<String, Object> additionalMetadata) {
        LinkedHashMap<String, Object> metadata = runMetadata(run);
        metadata.put("stepId", step.id());
        metadata.put("stepOrdinal", step.ordinal());
        metadata.put("operation", step.operation());
        metadata.put("changesData", step.changesData());
        metadata.put("requiresApproval", step.requiresApproval());
        if (step.actionName() != null && !step.actionName().isBlank()) metadata.put("actionName", step.actionName());
        if (additionalMetadata != null) metadata.putAll(additionalMetadata);
        auditRun(run, action, outcome, detail, metadata);
    }

    private void auditRun(StoredRun run, String action, String outcome, String detail,
                          Map<String, Object> metadata) {
        audit.record(actor(), action, "AI", "AGENT_RUN", String.valueOf(run.id()),
                "Agent run " + run.id(), outcome, detail, toJson(metadata));
    }

    private static LinkedHashMap<String, Object> runMetadata(StoredRun run) {
        LinkedHashMap<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("status", run.status());
        metadata.put("fingerprint", run.fingerprint());
        metadata.put("provider", run.providerId());
        metadata.put("model", run.modelName());
        metadata.put("modelAssisted", run.modelAssisted());
        metadata.put("riskLevel", run.riskLevel());
        metadata.put("confidence", run.confidence());
        metadata.put("stepCount", run.steps() == null ? 0 : run.steps().size());
        return metadata;
    }

    private static int answerCount(JsonNode answers) {
        if (answers == null || answers.isNull()) return 0;
        if (answers.isContainerNode()) return answers.size();
        return 1;
    }

    private String toJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
