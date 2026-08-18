package io.forgetdm.automation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.mapping.MappingExecutionService;
import io.forgetdm.mapping.MappingRunEntity;
import io.forgetdm.provision.DataScopeJobService;
import io.forgetdm.provision.ProvisioningService;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.reservation.ReservationEntity;
import io.forgetdm.reservation.ReservationService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.virtualization.VirtualizationService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Service
public class EnterpriseSelfServiceService {
    private static final Set<String> PRODUCT_TYPES = Set.of(
            "DATASCOPE", "SYNTHETIC", "MAPPING", "RESERVATION",
            "VDB_PROVISION", "VDB_REFRESH", "VDB_ROLLBACK");
    private static final Set<String> APPROVAL_MODES = Set.of("REQUIRED", "OPTIONAL", "NONE");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final DataScopeJobService dataScope;
    private final SyntheticGenService synthetic;
    private final MappingExecutionService mappings;
    private final ReservationService reservations;
    private final VirtualizationService virtualization;
    private final IntegrationWebhookService integrations;
    private final AuditService audit;

    public EnterpriseSelfServiceService(JdbcTemplate jdbc, ObjectMapper json, DataScopeJobService dataScope,
                                        SyntheticGenService synthetic, MappingExecutionService mappings,
                                        ReservationService reservations, VirtualizationService virtualization,
                                        IntegrationWebhookService integrations, AuditService audit) {
        this.jdbc = jdbc; this.json = json; this.dataScope = dataScope; this.synthetic = synthetic;
        this.mappings = mappings; this.reservations = reservations; this.virtualization = virtualization;
        this.integrations = integrations; this.audit = audit;
    }

    public record ProductRequest(String productType, String artifactId, Integer artifactVersion, String label,
                                 String description, String category, String tags, Boolean enabled,
                                 String approvalMode, JsonNode questionnaire, JsonNode guardrails,
                                 List<String> allowedEnvironments, String deliveryInstructions) {}
    public record OrderRequest(String productId, String purpose, String testType, String environment,
                               Map<String, Object> parameters, Long requestedVolume, String requestedVariety,
                               String deliveryMode, Boolean reservationRequested, Integer reservationHours,
                               Instant scheduleAt) {}
    public record Decision(String note) {}
    public record Comment(String message) {}

    public List<Map<String, Object>> catalog(String query, String category, String type) {
        syncLegacyDataScopeProducts();
        StringBuilder sql = new StringBuilder("SELECT * FROM self_service_products WHERE enabled=TRUE");
        List<Object> args = new ArrayList<>();
        if (clean(type) != null) { sql.append(" AND product_type=?"); args.add(type.trim().toUpperCase(Locale.ROOT)); }
        if (clean(category) != null) { sql.append(" AND LOWER(category)=LOWER(?)"); args.add(category.trim()); }
        if (clean(query) != null) {
            sql.append(" AND (LOWER(label) LIKE ? OR LOWER(COALESCE(description,'')) LIKE ? OR LOWER(COALESCE(tags,'')) LIKE ?)");
            String like = "%" + query.trim().toLowerCase(Locale.ROOT) + "%"; args.add(like); args.add(like); args.add(like);
        }
        sql.append(" ORDER BY category,label");
        return jdbc.query(sql.toString(), (rs, rowNum) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", rs.getString("id")); out.put("productType", rs.getString("product_type"));
            out.put("artifactId", rs.getString("artifact_id")); out.put("artifactVersion", rs.getObject("artifact_version"));
            out.put("label", rs.getString("label")); out.put("description", rs.getString("description"));
            out.put("category", rs.getString("category")); out.put("tags", csv(rs.getString("tags")));
            out.put("ownerUsername", rs.getString("owner_username")); out.put("approvalMode", rs.getString("approval_mode"));
            out.put("questionnaire", parseMap(rs.getString("questionnaire_json")));
            Map<String, Object> guardrails = parseMap(rs.getString("guardrails_json"));
            out.put("guardrails", guardrails);
            out.put("capabilities", capabilityContract(rs.getString("product_type"), guardrails));
            out.put("allowedEnvironments", csv(rs.getString("allowed_environments")));
            out.put("deliveryInstructions", rs.getString("delivery_instructions"));
            out.put("updatedAt", instant(rs.getTimestamp("updated_at")));
            return out;
        }, args.toArray());
    }

    public List<Map<String, Object>> products() {
        requireManager();
        return jdbc.queryForList("SELECT id AS \"id\",product_type AS \"productType\",artifact_id AS \"artifactId\",label AS \"label\",category AS \"category\",enabled AS \"enabled\",approval_mode AS \"approvalMode\",owner_username AS \"ownerUsername\",updated_at AS \"updatedAt\" FROM self_service_products ORDER BY updated_at DESC");
    }

    public Map<String, Object> publish(ProductRequest request) {
        AccessPrincipal actor = requireManager();
        String type = upper(required(request == null ? null : request.productType(), "Product type"));
        if (!PRODUCT_TYPES.contains(type)) throw ApiException.bad("Unsupported self-service product type: " + type);
        String artifactId = required(request.artifactId(), "Artifact");
        validateArtifact(type, artifactId);
        String label = required(request.label(), "Catalog label");
        String approval = upper(clean(request.approvalMode()) == null ? "REQUIRED" : request.approvalMode());
        if (!APPROVAL_MODES.contains(approval)) throw ApiException.bad("Approval mode must be REQUIRED, OPTIONAL, or NONE");
        String questionnaire = objectJson(request.questionnaire());
        String guardrails = objectJson(request.guardrails());
        String environments = join(request.allowedEnvironments());
        String tags = clean(request.tags());
        Instant now = Instant.now();
        List<String> existing = jdbc.query("SELECT id FROM self_service_products WHERE product_type=? AND artifact_id=?", (rs, n) -> rs.getString(1), type, artifactId);
        String id = existing.isEmpty() ? UUID.randomUUID().toString() : existing.get(0);
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO self_service_products(id,product_type,artifact_id,artifact_version,label,description,category,tags,owner_user_id,owner_username,enabled,approval_mode,questionnaire_json,guardrails_json,allowed_environments,delivery_instructions,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    id, type, artifactId, request.artifactVersion(), label, clean(request.description()), clean(request.category()), tags,
                    actor.userId(), actor.username(), !Boolean.FALSE.equals(request.enabled()), approval, questionnaire, guardrails,
                    environments, clean(request.deliveryInstructions()), ts(now), ts(now));
        } else {
            jdbc.update("UPDATE self_service_products SET artifact_version=?,label=?,description=?,category=?,tags=?,enabled=?,approval_mode=?,questionnaire_json=?,guardrails_json=?,allowed_environments=?,delivery_instructions=?,updated_at=? WHERE id=?",
                    request.artifactVersion(), label, clean(request.description()), clean(request.category()), tags,
                    !Boolean.FALSE.equals(request.enabled()), approval, questionnaire, guardrails, environments,
                    clean(request.deliveryInstructions()), ts(now), id);
        }
        if ("DATASCOPE".equals(type)) jdbc.update("UPDATE datascope_saved_jobs SET self_service_enabled=TRUE,self_service_label=? WHERE id=?", label, artifactId);
        audit.record(actor.username(), "SELF_SERVICE_PRODUCT_PUBLISHED", "SELF_SERVICE",
                "self-service-product", id, label, "SUCCESS", "Published self-service product",
                toJson(Map.of("productType", type, "artifactId", artifactId, "approvalMode", approval,
                        "enabled", !Boolean.FALSE.equals(request.enabled()))));
        return product(id);
    }

    public Map<String, Object> setEnabled(String id, boolean enabled) {
        AccessPrincipal actor = requireManager();
        int changed = jdbc.update("UPDATE self_service_products SET enabled=?,updated_at=? WHERE id=?", enabled, ts(Instant.now()), id);
        if (changed == 0) throw ApiException.notFound("Self-service product " + id + " not found");
        audit.record(actor.username(), enabled ? "SELF_SERVICE_PRODUCT_ENABLED" : "SELF_SERVICE_PRODUCT_DISABLED",
                "SELF_SERVICE", "self-service-product", id, str(product(id).get("label")), "SUCCESS",
                (enabled ? "Enabled" : "Disabled") + " self-service product",
                toJson(Map.of("enabled", enabled)));
        return product(id);
    }

    public List<Map<String, Object>> candidates() {
        requireManager();
        List<Map<String, Object>> out = new ArrayList<>();
        out.addAll(candidateRows("DATASCOPE", "SELECT id,name,description FROM datascope_saved_jobs ORDER BY updated_at DESC"));
        out.addAll(candidateRows("SYNTHETIC", "SELECT id,name,description FROM synthetic_saved_jobs WHERE approval_status='APPROVED' ORDER BY updated_at DESC"));
        out.addAll(candidateRows("MAPPING", "SELECT CAST(id AS VARCHAR) AS id,name,description FROM mapping_definitions ORDER BY updated_at DESC"));
        out.addAll(candidateRows("VDB_PROVISION", "SELECT CAST(id AS VARCHAR) AS id,name,note AS description FROM virtual_snapshots ORDER BY created_at DESC"));
        out.addAll(candidateRows("VDB_REFRESH", "SELECT CAST(id AS VARCHAR) AS id,name,'' AS description FROM virtual_databases ORDER BY created_at DESC"));
        out.addAll(candidateRows("VDB_ROLLBACK", "SELECT CAST(id AS VARCHAR) AS id,name,'' AS description FROM virtual_databases ORDER BY created_at DESC"));
        return out;
    }

    @Transactional
    public Map<String, Object> request(OrderRequest request) {
        AccessPrincipal actor = current();
        Map<String, Object> product = product(required(request == null ? null : request.productId(), "Product"));
        if (!Boolean.TRUE.equals(product.get("enabled"))) throw ApiException.bad("This catalog product is not currently available");
        String purpose = required(request.purpose(), "Test objective / business purpose");
        if (purpose.length() > 1000) throw ApiException.bad("Purpose must be 1000 characters or fewer");
        validateOrder(request, product);
        String approvalMode = String.valueOf(product.get("approvalMode"));
        boolean adminBypass = actor.hasPermission("admin.all");
        boolean preApproved = adminBypass || "NONE".equals(approvalMode);
        String status = preApproved ? "APPROVED" : "PENDING_APPROVAL";
        Long decisionById = adminBypass ? actor.userId() : null;
        String decisionBy = adminBypass ? actor.username() : preApproved ? "PRODUCT_POLICY" : null;
        String decisionNote = adminBypass ? "Administrator bypass policy" : preApproved ? "Pre-approved catalog product policy" : null;
        String id = UUID.randomUUID().toString(); Instant now = Instant.now();
        jdbc.update("INSERT INTO self_service_orders(id,product_id,product_type,artifact_id,product_label,requested_by_id,requested_by,purpose,test_type,environment,parameters_json,requested_volume,requested_variety,delivery_mode,reservation_requested,reservation_hours,schedule_at,status,decision_by_id,decision_by,decision_note,decided_at,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, product.get("id"), product.get("productType"), product.get("artifactId"), product.get("label"),
                actor.userId(), actor.username(), purpose, clean(request.testType()), clean(request.environment()),
                toJson(request.parameters() == null ? Map.of() : request.parameters()), request.requestedVolume(), clean(request.requestedVariety()),
                clean(request.deliveryMode()), Boolean.TRUE.equals(request.reservationRequested()), request.reservationHours(),
                request.scheduleAt() == null ? null : ts(request.scheduleAt()), status,
                decisionById, decisionBy, decisionNote, preApproved ? ts(now) : null, ts(now), ts(now));
        event(id, "REQUESTED", actor.username(), purpose, Map.of(
                "status", status,
                "approvalMode", adminBypass ? "ADMIN_BYPASS" : approvalMode));
        if (preApproved) {
            event(id, "APPROVED", decisionBy, decisionNote,
                    Map.of("approvalMode", adminBypass ? "ADMIN_BYPASS" : "PRODUCT_POLICY"));
        }
        audit.record(actor.username(), "SELF_SERVICE_ORDER_REQUESTED", "SELF_SERVICE",
                "self-service-order", id, String.valueOf(product.get("label")), "SUCCESS",
                "Requested self-service product",
                toJson(Map.of("productId", String.valueOf(product.get("id")),
                        "productType", String.valueOf(product.get("productType")),
                        "status", status, "approvalMode", adminBypass ? "ADMIN_BYPASS" : approvalMode,
                        "purposeLength", purpose.length())));
        integrations.emit("SELF_SERVICE_REQUESTED", Map.of("requestId", id, "productId", product.get("id"), "requestedBy", actor.username(), "purpose", purpose));
        return get(id);
    }

    public List<Map<String, Object>> orders() {
        AccessPrincipal actor = current();
        String sql = orderSelect();
        if (actor.hasPermission("provision.approve") || actor.hasPermission("admin.all"))
            return jdbc.queryForList(sql + " ORDER BY o.created_at DESC");
        return jdbc.queryForList(sql + " WHERE o.requested_by_id=? ORDER BY o.created_at DESC", actor.userId());
    }

    public Map<String, Object> get(String id) {
        AccessPrincipal actor = current();
        Map<String, Object> order = rawOrderView(id);
        long owner = ((Number) order.get("requestedById")).longValue();
        if (actor.userId() != owner && !actor.hasPermission("provision.approve") && !actor.hasPermission("admin.all"))
            throw ApiException.forbidden("You cannot view this self-service request");
        order.put("events", events(id));
        return order;
    }

    @Transactional
    public Map<String, Object> decide(String id, boolean approve, Decision decision) {
        AccessPrincipal actor = current();
        if (!actor.hasPermission("provision.approve") && !actor.hasPermission("admin.all")) throw ApiException.forbidden("Provision approval permission is required");
        Map<String, Object> order = rawOrder(id);
        if (!"PENDING_APPROVAL".equals(order.get("status"))) throw ApiException.bad("Request is not awaiting approval");
        if (actor.userId() == ((Number) order.get("requested_by_id")).longValue()) throw ApiException.bad("Maker-checker approval requires a different user");
        String note = required(decision == null ? null : decision.note(), approve ? "Approval note / e-signature reason" : "Rejection reason");
        String status = approve ? "APPROVED" : "REJECTED"; Instant now = Instant.now();
        jdbc.update("UPDATE self_service_orders SET status=?,decision_by_id=?,decision_by=?,decision_note=?,decided_at=?,updated_at=? WHERE id=? AND status='PENDING_APPROVAL'",
                status, actor.userId(), actor.username(), note, ts(now), ts(now), id);
        event(id, status, actor.username(), note, Map.of());
        audit.record(actor.username(), "SELF_SERVICE_ORDER_" + status, "SELF_SERVICE",
                "self-service-order", id, String.valueOf(order.get("product_label")), "SUCCESS",
                (approve ? "Approved" : "Rejected") + " self-service order",
                toJson(Map.of("decision", status, "requester", String.valueOf(order.get("requested_by")),
                        "reviewer", actor.username(), "noteLength", note.length())));
        integrations.emit("SELF_SERVICE_" + status, Map.of("requestId", id, "decisionBy", actor.username(), "note", note));
        return get(id);
    }

    @Transactional
    public Map<String, Object> fulfill(String id) {
        AccessPrincipal actor = current(); Map<String, Object> order = rawOrder(id);
        long requester = ((Number) order.get("requested_by_id")).longValue();
        if (actor.userId() != requester && !actor.hasPermission("admin.all")) throw ApiException.forbidden("Only the requester or an administrator can launch this request");
        if (!"APPROVED".equals(order.get("status"))) throw ApiException.bad("Request must be approved before launch");
        Timestamp scheduled = (Timestamp) order.get("schedule_at");
        if (scheduled != null && scheduled.toInstant().isAfter(Instant.now())) throw ApiException.bad("This request is scheduled for " + scheduled.toInstant());
        Map<String, Object> product = product(String.valueOf(order.get("product_id")));
        if (!Boolean.TRUE.equals(product.get("enabled"))) throw ApiException.bad("This catalog product has been disabled");
        Map<String, Object> parameters = parseMap((String) order.get("parameters_json"));
        if (order.get("requested_volume") != null) parameters.put("_requestedVolume", order.get("requested_volume"));
        ProvisioningService.ApprovalEvidence approvalEvidence = new ProvisioningService.ApprovalEvidence(
                "SELF_SERVICE_ORDER", id,
                firstText(str(order.get("decision_by")), str(order.get("requested_by")), "PRODUCT_POLICY"),
                firstText(str(order.get("decision_note")), "Approved self-service request"));
        Execution execution = execute(String.valueOf(order.get("product_type")), String.valueOf(order.get("artifact_id")), parameters, order, approvalEvidence);
        Instant now = Instant.now();
        jdbc.update("UPDATE self_service_orders SET status='SUBMITTED',run_type=?,run_ref=?,result_json=?,fulfilled_at=?,updated_at=? WHERE id=? AND status='APPROVED'",
                execution.type(), execution.reference(), toJson(execution.result()), ts(now), ts(now), id);
        event(id, "SUBMITTED", actor.username(), "Execution submitted", execution.result());
        audit.record(actor.username(), "SELF_SERVICE_ORDER_FULFILLED", "SELF_SERVICE",
                "self-service-order", id, String.valueOf(order.get("product_label")), "SUCCESS",
                "Fulfilled self-service order",
                toJson(Map.of("runType", execution.type(), "runRef", execution.reference())));
        integrations.emit("SELF_SERVICE_FULFILLED", Map.of("requestId", id, "runId", execution.reference(), "runType", execution.type()));
        return get(id);
    }

    public Map<String, Object> executionStatus(String id) {
        Map<String, Object> order = get(id);
        String runType = clean(str(order.get("runType")));
        String runRef = clean(str(order.get("runRef")));
        if (runType == null || runRef == null) {
            return executionView(id, null, null, String.valueOf(order.get("status")), "Not launched",
                    0, "This request has not launched an execution yet.", 0, 0, 0,
                    order.get("createdAt"), null, List.of(), null);
        }

        Map<String, Object> status = switch (runType) {
            case "DATASCOPE" -> dataScopeExecution(runRef);
            case "SYNTHETIC" -> syntheticExecution(runRef);
            case "MAPPING" -> mappingExecution(runRef);
            case "RESERVATION" -> reservationExecution(runRef);
            case "VIRTUALIZATION" -> virtualizationExecution(runRef);
            default -> executionView(id, runType, runRef, "UNKNOWN", "Unknown execution",
                    0, "Unsupported execution type " + runType, 0, 0, 0,
                    order.get("fulfilledAt"), null, List.of(), null);
        };
        String normalized = normalizeExecutionStatus(str(status.get("status")));
        status.put("status", normalized);
        status.put("requestId", id);
        status.put("runType", runType);
        status.put("runRef", runRef);
        synchronizeOrderStatus(id, String.valueOf(order.get("status")), normalized, status);
        return status;
    }

    public Map<String, Object> cancel(String id, Comment reason) {
        AccessPrincipal actor = current(); Map<String, Object> order = rawOrder(id);
        long requester = ((Number) order.get("requested_by_id")).longValue();
        if (actor.userId() != requester && !actor.hasPermission("admin.all")) throw ApiException.forbidden("You cannot cancel this request");
        if (!Set.of("PENDING_APPROVAL", "APPROVED").contains(String.valueOf(order.get("status")))) throw ApiException.bad("Only pending or approved requests can be canceled");
        String note = required(reason == null ? null : reason.message(), "Cancellation reason");
        jdbc.update("UPDATE self_service_orders SET status='CANCELED',canceled_at=?,updated_at=? WHERE id=?", ts(Instant.now()), ts(Instant.now()), id);
        event(id, "CANCELED", actor.username(), note, Map.of());
        audit.record(actor.username(), "SELF_SERVICE_ORDER_CANCELED", "SELF_SERVICE",
                "self-service-order", id, String.valueOf(order.get("product_label")), "SUCCESS",
                "Canceled self-service order",
                toJson(Map.of("requester", String.valueOf(order.get("requested_by")),
                        "previousStatus", String.valueOf(order.get("status")),
                        "reasonLength", note.length())));
        return get(id);
    }

    public Map<String, Object> comment(String id, Comment comment) {
        Map<String, Object> order = get(id); AccessPrincipal actor = current();
        String message = required(comment == null ? null : comment.message(), "Comment");
        event(id, "COMMENT", actor.username(), message, Map.of());
        audit.record(actor.username(), "SELF_SERVICE_ORDER_COMMENTED", "SELF_SERVICE",
                "self-service-order", id, String.valueOf(order.get("productLabel")), "SUCCESS",
                "Commented on self-service order",
                toJson(Map.of("status", String.valueOf(order.get("status")), "messageLength", message.length())));
        return get(id);
    }

    public Map<String, Object> runner(String id) {
        Map<String, Object> order = get(id);
        String path = "/api/self-service/v2/orders/" + id;
        String curl = "curl -sS -X POST \"$FORGETDM_URL" + path + "/fulfill\" -H \"Authorization: Bearer $FORGETDM_TOKEN\" -H \"Content-Type: application/json\" -d '{}'";
        String status = "curl -sS \"$FORGETDM_URL" + path + "\" -H \"Authorization: Bearer $FORGETDM_TOKEN\"";
        AccessPrincipal actor = current();
        audit.record(actor.username(), "SELF_SERVICE_ORDER_RUNNER_EXPORTED", "SELF_SERVICE",
                "self-service-order", id, String.valueOf(order.get("productLabel")), "SUCCESS",
                "Exported self-service runner commands",
                toJson(Map.of("status", String.valueOf(order.get("status")),
                        "productType", String.valueOf(order.get("productType")),
                        "artifactId", String.valueOf(order.get("artifactId")))));
        return Map.of("requestId", id, "product", order.get("productLabel"), "launchCommand", curl, "statusCommand", status,
                "note", "Use a personal API token with provision.run; secret values are never embedded in the command.");
    }

    public Map<String, Object> metrics() {
        AccessPrincipal actor = current();
        List<Map<String, Object>> orders = orders();
        Map<String, Long> statuses = new LinkedHashMap<>();
        long completed = 0, seconds = 0;
        for (Map<String, Object> order : orders) {
            statuses.merge(String.valueOf(order.get("status")), 1L, Long::sum);
            Instant created = toInstant(order.get("createdAt")); Instant fulfilled = toInstant(order.get("fulfilledAt"));
            if (created != null && fulfilled != null) { completed++; seconds += Math.max(0, Duration.between(created, fulfilled).toSeconds()); }
        }
        return Map.of("visibleRequests", orders.size(), "statusCounts", statuses,
                "averageFulfillmentSeconds", completed == 0 ? 0 : seconds / completed,
                "scope", actor.hasPermission("provision.approve") || actor.hasPermission("admin.all") ? "TEAM" : "PERSONAL");
    }

    private Execution execute(String type, String artifactId, Map<String, Object> p, Map<String, Object> order,
                              ProvisioningService.ApprovalEvidence approvalEvidence) {
        return switch (type) {
            case "DATASCOPE" -> execution("DATASCOPE", dataScope.runSelfService(artifactId, p, approvalEvidence));
            case "SYNTHETIC" -> execution("SYNTHETIC", synthetic.runSelfServiceSavedJob(artifactId));
            case "MAPPING" -> {
                ObjectNode request = json.valueToTree(p);
                String mappingName = jdbc.queryForObject("SELECT name FROM mapping_definitions WHERE id=?", String.class, Long.valueOf(artifactId));
                request.put("confirmation", mappingName); request.putIfAbsent("seed", json.getNodeFactory().textNode("self-service-" + order.get("id")));
                MappingRunEntity run = mappings.start(Long.valueOf(artifactId), request);
                yield new Execution("MAPPING", String.valueOf(run.getId()), Map.of("runId", run.getId(), "status", run.getStatus()));
            }
            case "RESERVATION" -> {
                ReservationEntity reservation = reservations.findAndReserve(longParam(p, "dataSourceId"), required(str(p.get("table")), "Table"),
                        str(p.get("criteria")), intParam(p, "count", 1), current().username(), String.valueOf(order.get("purpose")),
                        intParam(p, "ttlHours", 24));
                yield new Execution("RESERVATION", String.valueOf(reservation.getId()), Map.of("reservationId", reservation.getId(), "status", reservation.getStatus(), "expiresAt", reservation.getExpiresAt().toString()));
            }
            case "VDB_PROVISION" -> execution("VIRTUALIZATION", virtualization.startProvision(Long.valueOf(artifactId), required(str(p.get("name")), "VDB name"), nullableLong(p.get("targetDataSourceId")), str(p.get("pointInTime")), nullableLong(p.get("environmentId"))));
            case "VDB_REFRESH" -> execution("VIRTUALIZATION", virtualization.startRefresh(Long.valueOf(artifactId), longParam(p, "snapshotId")));
            case "VDB_ROLLBACK" -> execution("VIRTUALIZATION", virtualization.startRewind(Long.valueOf(artifactId), longParam(p, "snapshotId")));
            default -> throw ApiException.bad("Unsupported self-service product type: " + type);
        };
    }

    private Execution execution(String type, Map<String, Object> result) {
        Object ref = result.get("runId"); if (ref == null) ref = result.get("id"); if (ref == null) ref = result.get("opId");
        return new Execution(type, ref == null ? "submitted" : String.valueOf(ref), result);
    }
    private record Execution(String type, String reference, Map<String, Object> result) {}

    private Map<String, Object> dataScopeExecution(String runRef) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, job_type AS "stage", message, rows_processed AS "rowsWritten",
                       created_at AS "startedAt", started_at AS "engineStartedAt",
                       finished_at AS "finishedAt", table_states_json AS "tableStates"
                  FROM provision_jobs WHERE id = ?
                """, Long.valueOf(runRef));
        if (rows.isEmpty()) return missingExecution("DATASCOPE", runRef);
        Map<String, Object> row = rows.get(0);
        String status = str(row.get("status"));
        List<Map<String, Object>> logs = new ArrayList<>();
        addLog(logs, row.get("engineStartedAt"), level(status), str(row.get("stage")), str(row.get("message")));
        JsonNode states = parseTree(str(row.get("tableStates")));
        if (states != null) {
            if (states.isArray()) states.forEach(state -> addLog(logs, null,
                    level(state.path("status").asText()), state.path("table").asText("Table"),
                    state.path("message").asText(state.path("status").asText())));
            else if (states.isObject()) states.fields().forEachRemaining(entry -> addLog(logs, null,
                    level(entry.getValue().path("status").asText()), entry.getKey(),
                    entry.getValue().path("message").asText(entry.getValue().path("status").asText())));
        }
        int progress = terminalSuccess(status) ? 100 : terminalFailure(status) ? 100 : "RUNNING".equalsIgnoreCase(status) ? 50 : 5;
        return executionView(null, "DATASCOPE", runRef, status, str(row.get("stage")), progress,
                str(row.get("message")), 0, number(row.get("rowsWritten"), 0), 0,
                first(row.get("engineStartedAt"), row.get("startedAt")), row.get("finishedAt"), logs, "/datascope");
    }

    private Map<String, Object> syntheticExecution(String runRef) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, stage, percent AS "progress", message, detail, current_table AS "currentTable",
                       table_rows_done AS "tableRowsDone", table_rows_total AS "tableRowsTotal",
                       rows_done AS "rowsWritten", rows_total AS "rowsTotal", error,
                       started_at AS "startedAt", finished_at AS "finishedAt"
                  FROM synthetic_generation_jobs WHERE id = ?
                """, runRef);
        if (rows.isEmpty()) return missingExecution("SYNTHETIC", runRef);
        Map<String, Object> row = rows.get(0);
        List<Map<String, Object>> logs = new ArrayList<>();
        addLog(logs, row.get("startedAt"), level(str(row.get("status"))),
                firstText(str(row.get("stage")), "Synthetic generation"),
                firstText(str(row.get("error")), str(row.get("message")), str(row.get("detail"))));
        List<Map<String, Object>> partitions = jdbc.queryForList("""
                SELECT table_name AS "tableName", partition_number AS "partitionNumber", status,
                       rows_completed AS "rowsCompleted", planned_rows AS "plannedRows",
                       attempt_count AS "attemptCount", error, started_at AS "startedAt",
                       finished_at AS "finishedAt"
                  FROM synthetic_job_partitions
                 WHERE job_id = ?
                 ORDER BY dependency_wave, table_name, partition_number
                 LIMIT 250
                """, runRef);
        for (Map<String, Object> partition : partitions) {
            String label = partition.get("tableName") + " #" + partition.get("partitionNumber");
            String message = partition.get("rowsCompleted") + " / " + partition.get("plannedRows") + " rows";
            if (clean(str(partition.get("error"))) != null) message += " - " + partition.get("error");
            addLog(logs, first(partition.get("finishedAt"), partition.get("startedAt")),
                    level(str(partition.get("status"))), label, message);
        }
        Map<String, Object> view = executionView(null, "SYNTHETIC", runRef, str(row.get("status")),
                str(row.get("stage")), (int) number(row.get("progress"), 0),
                firstText(str(row.get("error")), str(row.get("message")), str(row.get("detail"))),
                0, number(row.get("rowsWritten"), 0), 0, row.get("startedAt"), row.get("finishedAt"),
                logs, "/synthetic");
        view.put("currentTable", row.get("currentTable"));
        view.put("rowsTotal", number(row.get("rowsTotal"), 0));
        view.put("tableRowsDone", number(row.get("tableRowsDone"), 0));
        view.put("tableRowsTotal", number(row.get("tableRowsTotal"), 0));
        view.put("partitionCount", partitions.size());
        return view;
    }

    private Map<String, Object> mappingExecution(String runRef) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, stage, progress, message, rows_read AS "rowsRead",
                       rows_written AS "rowsWritten", rows_rejected AS "rowsRejected",
                       error_message AS "error", started_at AS "startedAt", finished_at AS "finishedAt"
                  FROM mapping_execution_runs WHERE id = ?
                """, Long.valueOf(runRef));
        if (rows.isEmpty()) return missingExecution("MAPPING", runRef);
        Map<String, Object> row = rows.get(0);
        List<Map<String, Object>> logs = new ArrayList<>();
        addLog(logs, first(row.get("finishedAt"), row.get("startedAt")), level(str(row.get("status"))),
                str(row.get("stage")), firstText(str(row.get("error")), str(row.get("message"))));
        return executionView(null, "MAPPING", runRef, str(row.get("status")), str(row.get("stage")),
                (int) number(row.get("progress"), 0), firstText(str(row.get("error")), str(row.get("message"))),
                number(row.get("rowsRead"), 0), number(row.get("rowsWritten"), 0),
                number(row.get("rowsRejected"), 0), row.get("startedAt"), row.get("finishedAt"),
                logs, "/mapping");
    }

    private Map<String, Object> reservationExecution(String runRef) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT status, table_name AS "tableName", reserved_by AS "reservedBy",
                       expires_at AS "expiresAt", created_at AS "startedAt"
                  FROM reservations WHERE id = ?
                """, Long.valueOf(runRef));
        if (rows.isEmpty()) return missingExecution("RESERVATION", runRef);
        Map<String, Object> row = rows.get(0);
        List<Map<String, Object>> logs = new ArrayList<>();
        addLog(logs, row.get("startedAt"), level(str(row.get("status"))), "Reservation",
                "Reserved " + row.get("tableName") + " for " + row.get("reservedBy"));
        Map<String, Object> view = executionView(null, "RESERVATION", runRef, str(row.get("status")),
                "Reservation", 100, "Reservation expires " + row.get("expiresAt"), 0, 0, 0,
                row.get("startedAt"), row.get("expiresAt"), logs, "/reservations");
        view.put("expiresAt", row.get("expiresAt"));
        return view;
    }

    private Map<String, Object> virtualizationExecution(String runRef) {
        try {
            Map<String, Object> operation = virtualization.operation(runRef);
            List<Map<String, Object>> stages = operation.get("stages") instanceof List<?> list
                    ? list.stream().filter(Map.class::isInstance).map(value -> (Map<String, Object>) value).toList()
                    : List.of();
            long completed = stages.stream().filter(stage -> "DONE".equalsIgnoreCase(str(stage.get("status")))).count();
            int progress = stages.isEmpty()
                    ? (terminalSuccess(str(operation.get("status"))) ? 100 : 5)
                    : (int) Math.min(100, completed * 100 / stages.size());
            List<Map<String, Object>> logs = new ArrayList<>();
            for (Map<String, Object> stage : stages) {
                addLog(logs, null, level(str(stage.get("status"))), str(stage.get("name")),
                        stage.get("elapsedMs") + " ms");
            }
            return executionView(null, "VIRTUALIZATION", runRef, str(operation.get("status")),
                    str(operation.get("kind")), progress,
                    firstText(str(operation.get("error")), str(operation.get("message"))),
                    0, 0, 0, operation.get("startedAt"), operation.get("finishedAt"), logs, "/virtualization");
        } catch (ApiException missing) {
            return missingExecution("VIRTUALIZATION", runRef);
        }
    }

    private Map<String, Object> missingExecution(String runType, String runRef) {
        return executionView(null, runType, runRef, "UNKNOWN", "Execution unavailable", 0,
                "The execution record is no longer available on this server.", 0, 0, 0,
                null, null, List.of(), modulePath(runType));
    }

    private Map<String, Object> executionView(String requestId, String runType, String runRef,
                                               String status, String stage, int progress, String message,
                                               long rowsRead, long rowsWritten, long rowsRejected,
                                               Object startedAt, Object finishedAt,
                                               List<Map<String, Object>> logs, String modulePath) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", requestId);
        out.put("runType", runType);
        out.put("runRef", runRef);
        out.put("status", normalizeExecutionStatus(status));
        out.put("stage", firstText(stage, "Submitted"));
        out.put("progress", Math.max(0, Math.min(100, progress)));
        out.put("message", firstText(message, "Execution submitted"));
        out.put("rowsRead", rowsRead);
        out.put("rowsWritten", rowsWritten);
        out.put("rowsRejected", rowsRejected);
        out.put("startedAt", startedAt);
        out.put("finishedAt", finishedAt);
        out.put("logs", logs == null ? List.of() : logs);
        out.put("modulePath", modulePath);
        return out;
    }

    private void synchronizeOrderStatus(String id, String previous, String current, Map<String, Object> detail) {
        if (Objects.equals(previous, current) || "UNKNOWN".equals(current)) return;
        if (Set.of("REJECTED", "CANCELED").contains(previous)) return;
        int changed = jdbc.update("UPDATE self_service_orders SET status=?, updated_at=? WHERE id=? AND status=?",
                current, ts(Instant.now()), id, previous);
        if (changed == 1) {
            event(id, current, "SYSTEM", firstText(str(detail.get("message")), "Execution status updated"),
                    Map.of("runType", Objects.toString(detail.get("runType"), ""),
                            "runRef", Objects.toString(detail.get("runRef"), ""),
                            "progress", number(detail.get("progress"), 0)));
        }
    }

    private static String normalizeExecutionStatus(String value) {
        String status = clean(value) == null ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
        if (Set.of("DONE", "COMPLETED", "SUCCESS", "SUCCEEDED", "ACTIVE", "RESERVED", "FULFILLED").contains(status))
            return "COMPLETED";
        if (Set.of("FAILED", "ERROR", "DEAD").contains(status)) return "FAILED";
        if (Set.of("CANCELED", "CANCELLED", "CANCELLING", "CANCEL_REQUESTED").contains(status)) return "CANCELED";
        if (Set.of("RUNNING", "IN_PROGRESS", "EXECUTING").contains(status)) return "RUNNING";
        if (Set.of("PENDING", "QUEUED", "SUBMITTED", "STARTING").contains(status)) return "SUBMITTED";
        return status;
    }

    private static boolean terminalSuccess(String status) {
        return "COMPLETED".equals(normalizeExecutionStatus(status));
    }

    private static boolean terminalFailure(String status) {
        return Set.of("FAILED", "CANCELED").contains(normalizeExecutionStatus(status));
    }

    private JsonNode parseTree(String value) {
        if (clean(value) == null) return null;
        try { return json.readTree(value); } catch (Exception ignored) { return null; }
    }

    private static void addLog(List<Map<String, Object>> logs, Object at, String level,
                               String label, String message) {
        if (clean(label) == null && clean(message) == null) return;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("at", at);
        row.put("level", firstText(level, "INFO"));
        row.put("label", firstText(label, "Execution"));
        row.put("message", firstText(message, "Status updated"));
        logs.add(row);
    }

    private static String level(String status) {
        String normalized = normalizeExecutionStatus(status);
        if ("FAILED".equals(normalized)) return "ERROR";
        if ("CANCELED".equals(normalized)) return "WARN";
        if ("COMPLETED".equals(normalized)) return "SUCCESS";
        return "INFO";
    }

    private static Object first(Object... values) {
        for (Object value : values) if (value != null) return value;
        return null;
    }

    private static String firstText(String... values) {
        for (String value : values) if (clean(value) != null) return value.trim();
        return null;
    }

    private static String modulePath(String runType) {
        return switch (Objects.toString(runType, "")) {
            case "DATASCOPE" -> "/datascope";
            case "SYNTHETIC" -> "/synthetic";
            case "MAPPING" -> "/mapping";
            case "RESERVATION" -> "/reservations";
            case "VIRTUALIZATION" -> "/virtualization";
            default -> null;
        };
    }

    private void validateOrder(OrderRequest request, Map<String, Object> product) {
        List<String> allowed = (List<String>) product.getOrDefault("allowedEnvironments", List.of());
        if (!allowed.isEmpty() && clean(request.environment()) != null && allowed.stream().noneMatch(e -> e.equalsIgnoreCase(request.environment())))
            throw ApiException.bad("Environment is outside this product's published guardrails");
        Map<String, Object> guardrails = (Map<String, Object>) product.getOrDefault("guardrails", Map.of());
        Map<String, Object> capabilities = capabilityContract(String.valueOf(product.get("productType")), guardrails);
        if (request.requestedVolume() != null && !Boolean.TRUE.equals(capabilities.get("supportsVolume")))
            throw ApiException.bad("Requested volume is fixed by this product and cannot be changed");
        if (clean(request.requestedVariety()) != null && !Boolean.TRUE.equals(capabilities.get("supportsVariety")))
            throw ApiException.bad("Data variation is fixed by this product and cannot be changed");
        if (Boolean.TRUE.equals(request.reservationRequested()) && !Boolean.TRUE.equals(capabilities.get("supportsReservation")))
            throw ApiException.bad("This product does not create a reservable data allocation");
        if (request.scheduleAt() != null && !Boolean.TRUE.equals(capabilities.get("supportsLaunchWindow")))
            throw ApiException.bad("This product does not support a delayed launch window");
        List<String> deliveryModes = (List<String>) capabilities.getOrDefault("deliveryModes", List.of());
        if (clean(request.deliveryMode()) != null && deliveryModes.stream().noneMatch(mode -> mode.equalsIgnoreCase(request.deliveryMode())))
            throw ApiException.bad("Delivery mode is not supported by this product");
        validateQuestionnaire(request.parameters(), product);
        long maxVolume = number(guardrails.get("maxVolume"), 0);
        if (request.requestedVolume() != null && request.requestedVolume() < 1) throw ApiException.bad("Requested volume must be positive");
        if (maxVolume > 0 && request.requestedVolume() != null && request.requestedVolume() > maxVolume)
            throw ApiException.bad("Requested volume exceeds the published maximum of " + maxVolume);
        int maxReservation = (int) number(guardrails.get("maxReservationHours"), 168);
        if (Boolean.TRUE.equals(request.reservationRequested()) && (request.reservationHours() == null || request.reservationHours() < 1 || request.reservationHours() > maxReservation))
            throw ApiException.bad("Reservation duration must be between 1 and " + maxReservation + " hours");
        if (request.scheduleAt() != null && request.scheduleAt().isBefore(Instant.now())) throw ApiException.bad("Scheduled time must be in the future");
    }

    @SuppressWarnings("unchecked")
    private void validateQuestionnaire(Map<String, Object> parameters, Map<String, Object> product) {
        Map<String, Object> submitted = parameters == null ? Map.of() : parameters;
        Map<String, Object> questionnaire = (Map<String, Object>) product.getOrDefault("questionnaire", Map.of());
        List<Map<String, Object>> fields = questionnaire.get("fields") instanceof List<?> list
                ? list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList()
                : List.of();
        Set<String> allowed = new LinkedHashSet<>(defaultParameterKeys(String.valueOf(product.get("productType"))));
        for (Map<String, Object> field : fields) {
            String key = clean(str(field.get("key")));
            if (key == null) continue;
            allowed.add(key);
            if (Boolean.TRUE.equals(field.get("required"))) {
                Object value = submitted.get(key);
                if (value == null || (value instanceof String text && text.isBlank()))
                    throw ApiException.bad(firstText(clean(str(field.get("label"))), key) + " is required");
            }
        }
        for (String key : submitted.keySet()) {
            if (key == null || key.startsWith("_scenario")) continue;
            if (!allowed.contains(key)) throw ApiException.bad("Unsupported request field: " + key);
        }
    }

    private static Set<String> defaultParameterKeys(String type) {
        return switch (type) {
            case "DATASCOPE" -> Set.of("selectionNote", "filter", "criteria", "targetSchema", "schema", "seed", "maskingSeed");
            case "MAPPING" -> Set.of("seed");
            case "RESERVATION" -> Set.of("dataSourceId", "table", "criteria", "count", "ttlHours");
            case "VDB_PROVISION" -> Set.of("name", "targetDataSourceId", "pointInTime", "environmentId");
            case "VDB_REFRESH", "VDB_ROLLBACK" -> Set.of("snapshotId");
            default -> Set.of();
        };
    }

    private static Map<String, Object> capabilityContract(String type, Map<String, Object> guardrails) {
        String normalized = upper(type);
        boolean dataScope = "DATASCOPE".equals(normalized);
        boolean vdbProvision = "VDB_PROVISION".equals(normalized);
        boolean vdbRefresh = "VDB_REFRESH".equals(normalized);
        boolean vdbRollback = "VDB_ROLLBACK".equals(normalized);
        List<String> deliveryModes = vdbProvision || vdbRefresh || vdbRollback
                ? List.of("VIRTUAL_DATABASE")
                : "RESERVATION".equals(normalized) ? List.of("RESERVATION")
                : List.of("DATABASE");
        List<String> systems = stringList(first(guardrails.get("systems"), guardrails.get("applications")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("supportsVolume", dataScope);
        out.put("supportsVariety", false);
        out.put("supportsReservation", false);
        out.put("supportsLaunchWindow", true);
        out.put("supportsPointInTime", vdbProvision);
        out.put("supportsRewind", vdbRollback);
        out.put("supportsRefresh", vdbRefresh);
        out.put("deliveryModes", deliveryModes);
        out.put("systems", systems);
        out.put("lockedControls", List.of("Source access", "Data relationships", "Masking and generation rules", "Physical target"));
        out.put("outcome", switch (normalized) {
            case "DATASCOPE" -> "Referentially intact masked data subset";
            case "SYNTHETIC" -> "Reusable synthetic design with its approved row and receiver plan";
            case "MAPPING" -> "Governed transformation and delivery run";
            case "RESERVATION" -> "Exclusive logical reservation from an approved data pool";
            case "VDB_PROVISION" -> "Reserved virtual database at an approved point in time";
            case "VDB_REFRESH" -> "Virtual database refreshed to an approved snapshot";
            case "VDB_ROLLBACK" -> "Virtual database rewound to a known-good snapshot";
            default -> "Governed test data delivery";
        });
        return out;
    }

    private static List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection)
            return collection.stream().map(String::valueOf).map(String::trim).filter(item -> !item.isBlank()).toList();
        if (value == null || String.valueOf(value).isBlank()) return List.of();
        return Arrays.stream(String.valueOf(value).split(",")).map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private void validateArtifact(String type, String id) {
        String sql = switch (type) {
            case "DATASCOPE" -> "SELECT COUNT(*) FROM datascope_saved_jobs WHERE id=?";
            case "SYNTHETIC" -> "SELECT COUNT(*) FROM synthetic_saved_jobs WHERE id=? AND approval_status='APPROVED'";
            case "MAPPING" -> "SELECT COUNT(*) FROM mapping_definitions WHERE id=?";
            case "VDB_PROVISION" -> "SELECT COUNT(*) FROM virtual_snapshots WHERE id=?";
            case "VDB_REFRESH", "VDB_ROLLBACK" -> "SELECT COUNT(*) FROM virtual_databases WHERE id=?";
            case "RESERVATION" -> "SELECT COUNT(*) FROM data_sources WHERE id=?";
            default -> throw ApiException.bad("Unsupported product type");
        };
        Object key = Set.of("MAPPING", "VDB_PROVISION", "VDB_REFRESH", "VDB_ROLLBACK", "RESERVATION").contains(type) ? Long.valueOf(id) : id;
        Integer count = jdbc.queryForObject(sql, Integer.class, key);
        if (count == null || count == 0) throw ApiException.notFound("Eligible " + type + " artifact " + id + " not found");
    }

    /** Keep previously published DataScope templates visible after the catalog upgrade. */
    private void syncLegacyDataScopeProducts() {
        try {
            List<Map<String, Object>> legacy = jdbc.queryForList("SELECT id,name,description,COALESCE(self_service_label,name) AS label,owner_user_id,owner_username,updated_at FROM datascope_saved_jobs WHERE self_service_enabled=TRUE");
            for (Map<String, Object> row : legacy) {
                String artifact = String.valueOf(row.get("id"));
                Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM self_service_products WHERE product_type='DATASCOPE' AND artifact_id=?", Integer.class, artifact);
                if (count != null && count > 0) continue;
                Instant now = toInstant(row.get("updated_at")); if (now == null) now = Instant.now();
                jdbc.update("INSERT INTO self_service_products(id,product_type,artifact_id,label,description,category,tags,owner_user_id,owner_username,enabled,approval_mode,questionnaire_json,guardrails_json,allowed_environments,delivery_instructions,created_at,updated_at) VALUES (?,'DATASCOPE',?,?,?,?,?,?,?,?,?,'{}','{}','DEV,QA,UAT,PERFORMANCE,TRAINING',?,?,?)",
                        UUID.randomUUID().toString(), artifact, String.valueOf(row.get("label")), clean(str(row.get("description"))),
                        "Masked subsets", "subset,masking,provision", row.get("owner_user_id"), Objects.toString(row.get("owner_username"), "system"),
                        true, "REQUIRED", "Published DataScope template. Protected source, target, policy, and relationship settings cannot be changed.", ts(now), ts(now));
            }
        } catch (Exception ignored) { /* Migration/startup ordering: retry on the next catalog read. */ }
    }

    private Map<String, Object> product(String id) {
        List<Map<String, Object>> rows = jdbc.query("SELECT * FROM self_service_products WHERE id=?", (rs, n) -> {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", rs.getString("id")); out.put("productType", rs.getString("product_type")); out.put("artifactId", rs.getString("artifact_id"));
            out.put("label", rs.getString("label")); out.put("description", rs.getString("description")); out.put("category", rs.getString("category"));
            out.put("enabled", rs.getBoolean("enabled")); out.put("approvalMode", rs.getString("approval_mode"));
            out.put("questionnaire", parseMap(rs.getString("questionnaire_json")));
            Map<String, Object> guardrails = parseMap(rs.getString("guardrails_json"));
            out.put("guardrails", guardrails);
            out.put("capabilities", capabilityContract(rs.getString("product_type"), guardrails));
            out.put("allowedEnvironments", csv(rs.getString("allowed_environments"))); out.put("deliveryInstructions", rs.getString("delivery_instructions"));
            return out;
        }, id);
        if (rows.isEmpty()) throw ApiException.notFound("Self-service product " + id + " not found");
        return rows.get(0);
    }

    private List<Map<String, Object>> candidateRows(String type, String sql) {
        try { return jdbc.query(sql, (rs, n) -> Map.of("productType", type, "artifactId", rs.getString("id"), "name", rs.getString("name"), "description", Objects.toString(rs.getString("description"), ""))); }
        catch (Exception ignored) { return List.of(); }
    }
    private List<Map<String, Object>> events(String id) { return jdbc.queryForList("SELECT event_type AS \"eventType\",actor AS \"actor\",message AS \"message\",detail_json AS \"detailJson\",created_at AS \"createdAt\" FROM self_service_order_events WHERE order_id=? ORDER BY created_at", id); }
    private void event(String id, String type, String actor, String message, Map<String, Object> detail) { jdbc.update("INSERT INTO self_service_order_events(order_id,event_type,actor,message,detail_json,created_at) VALUES (?,?,?,?,?,?)", id, type, actor, clean(message), toJson(detail), ts(Instant.now())); }
    private Map<String, Object> rawOrder(String id) { List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM self_service_orders WHERE id=?", id); if (rows.isEmpty()) throw ApiException.notFound("Self-service request " + id + " not found"); return rows.get(0); }
    private Map<String, Object> rawOrderView(String id) { List<Map<String, Object>> rows = jdbc.queryForList(orderSelect() + " WHERE o.id=?", id); if (rows.isEmpty()) throw ApiException.notFound("Self-service request " + id + " not found"); return rows.get(0); }
    private static String orderSelect() { return "SELECT o.id AS \"id\",o.product_id AS \"productId\",o.product_type AS \"productType\",o.artifact_id AS \"artifactId\",o.product_label AS \"productLabel\",o.requested_by_id AS \"requestedById\",o.requested_by AS \"requestedBy\",o.purpose AS \"purpose\",o.test_type AS \"testType\",o.environment AS \"environment\",o.parameters_json AS \"parametersJson\",o.requested_volume AS \"requestedVolume\",o.requested_variety AS \"requestedVariety\",o.delivery_mode AS \"deliveryMode\",o.reservation_requested AS \"reservationRequested\",o.reservation_hours AS \"reservationHours\",o.schedule_at AS \"scheduleAt\",o.status AS \"status\",o.decision_by AS \"decisionBy\",o.decision_note AS \"decisionNote\",o.run_type AS \"runType\",o.run_ref AS \"runRef\",o.result_json AS \"resultJson\",o.created_at AS \"createdAt\",o.decided_at AS \"decidedAt\",o.fulfilled_at AS \"fulfilledAt\",o.updated_at AS \"updatedAt\" FROM self_service_orders o"; }

    private AccessPrincipal requireManager() { AccessPrincipal p = current(); if (!p.hasPermission("datascope.manage") && !p.hasPermission("admin.all")) throw ApiException.forbidden("Catalog management permission is required"); return p; }
    private static AccessPrincipal current() { return AccessContext.current().orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Login required")); }
    private String objectJson(JsonNode node) { if (node == null || node.isNull()) return "{}"; if (!node.isObject()) throw ApiException.bad("Questionnaire and guardrails must be JSON objects"); return node.toString(); }
    private String toJson(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw ApiException.bad("Could not serialize self-service configuration"); } }
    private Map<String, Object> parseMap(String value) { if (value == null || value.isBlank()) return new LinkedHashMap<>(); try { return json.readValue(value, new TypeReference<LinkedHashMap<String, Object>>() {}); } catch (Exception e) { return new LinkedHashMap<>(); } }
    private static long longParam(Map<String, Object> p, String key) { Long value = nullableLong(p.get(key)); if (value == null) throw ApiException.bad(key + " is required"); return value; }
    private static int intParam(Map<String, Object> p, String key, int fallback) { Object value = p.get(key); return value == null || String.valueOf(value).isBlank() ? fallback : Integer.parseInt(String.valueOf(value)); }
    private static Long nullableLong(Object value) { return value == null || String.valueOf(value).isBlank() ? null : Long.valueOf(String.valueOf(value)); }
    private static long number(Object value, long fallback) { if (value == null) return fallback; try { return Long.parseLong(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private static String str(Object value) { return value == null ? null : String.valueOf(value); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String required(String value, String label) { String clean = clean(value); if (clean == null) throw ApiException.bad(label + " is required"); return clean; }
    private static String upper(String value) { return value.toUpperCase(Locale.ROOT); }
    private static String join(List<String> values) { return values == null ? null : String.join(",", values.stream().map(EnterpriseSelfServiceService::clean).filter(Objects::nonNull).toList()); }
    private static List<String> csv(String value) { return value == null || value.isBlank() ? List.of() : Arrays.stream(value.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList(); }
    private static Timestamp ts(Instant value) { return Timestamp.from(value); }
    private static String instant(Timestamp value) { return value == null ? null : value.toInstant().toString(); }
    private static Instant toInstant(Object value) { if (value instanceof Timestamp t) return t.toInstant(); if (value instanceof Instant i) return i; if (value == null) return null; try { return Instant.parse(String.valueOf(value)); } catch (Exception e) { return null; } }
}
