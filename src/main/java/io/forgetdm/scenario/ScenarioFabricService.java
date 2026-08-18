package io.forgetdm.scenario;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.automation.EnterpriseSelfServiceService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.topology.TopologyService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

@Service
public class ScenarioFabricService {
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "GROUP", "SHARED");
    private static final Set<String> STRATEGIES = Set.of(
            "AUTO", "SUBSET", "SYNTHETIC", "HYBRID", "CLONE", "SNAPSHOT");
    private static final Set<String> ASSET_TYPES = Set.of(
            "SELF_SERVICE_PRODUCT", "BUSINESS_ENTITY", "DATASCOPE", "SYNTHETIC",
            "MAPPING", "VIRTUALIZATION", "MASKING_POLICY");

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final OwnershipGuard ownership;
    private final TopologyService topologies;
    private final EnterpriseSelfServiceService selfService;
    private final ScenarioCompiler compiler;
    private final AuditService audit;

    public ScenarioFabricService(JdbcTemplate jdbc, ObjectMapper json, OwnershipGuard ownership,
                                 TopologyService topologies, EnterpriseSelfServiceService selfService,
                                 ScenarioCompiler compiler, AuditService audit) {
        this.jdbc = jdbc;
        this.json = json;
        this.ownership = ownership;
        this.topologies = topologies;
        this.selfService = selfService;
        this.compiler = compiler;
        this.audit = audit;
    }

    public List<DomainSummary> domains() {
        return jdbc.query("""
                        SELECT d.*,
                               (SELECT COUNT(*) FROM scenario_blueprints b WHERE b.domain_id=d.id) blueprint_count,
                               (SELECT COUNT(*) FROM scenario_domain_assets a WHERE a.domain_id=d.id) asset_count,
                               (SELECT COUNT(*) FROM scenario_missions m WHERE m.domain_id=d.id) mission_count
                          FROM scenario_domains d
                         ORDER BY d.updated_at DESC, LOWER(d.name)
                        """, (rs, row) -> domainSummary(rs))
                .stream()
                .filter(row -> ownership.canSee(row.ownerUserId(), row.ownerGroupId(), row.visibility()))
                .toList();
    }

    @Transactional
    public DomainDetail publish(PublishDomain request) {
        if (request == null || request.topologyId() == null) throw ApiException.bad("Topology is required");
        TopologyService.TopologySummary topology = topologies.get(request.topologyId());
        if (topology.currentVersion() < 1 || topology.nodeCount() < 1 || topology.currentHash() == null) {
            throw ApiException.bad("Discover this topology successfully before publishing a Test Domain");
        }
        String name = validName(firstText(request.name(), topology.name()), "Test Domain");
        ensureDomainNameAvailable(name);
        String visibility = visibility(firstText(request.visibility(), topology.visibility()));

        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("sourceCount", topology.sourceCount());
        settings.put("nodeCount", topology.nodeCount());
        settings.put("relationshipCount", topology.edgeCount());
        settings.put("topologyStatus", topology.status());
        settings.put("publishedFrom", "DATA_TOPOLOGY");
        settings.put("publishedAt", Instant.now().toString());

        long id = insert("""
                        INSERT INTO scenario_domains
                          (topology_id,topology_version,topology_hash,name,business_domain,description,
                           settings_json,owner_user_id,owner_username,owner_group_id,visibility)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)
                        """, topology.id(), topology.currentVersion(), topology.currentHash(), name,
                trim(firstText(request.businessDomain(), topology.domain()), 120),
                trim(firstText(request.description(), topology.description()), 2000), write(settings),
                ownership.defaultOwnerUserId(), actor(), ownership.defaultOwnerGroupId(), visibility);

        if (!Boolean.FALSE.equals(request.createStarterBlueprint())) {
            createBlueprintInternal(id, starterBlueprint(name, topology.domain()));
        }
        audit.record(actor(), "TEST_DOMAIN_PUBLISHED", "SCENARIO_FABRIC", "test-domain",
                String.valueOf(id), name, "SUCCESS",
                "topology=" + topology.id() + " version=" + topology.currentVersion(), null);
        return domain(id);
    }

    public DomainDetail domain(long id) {
        DomainRow row = requireDomain(id);
        DomainSummary summary = domains().stream().filter(item -> item.id() == id).findFirst()
                .orElseThrow(() -> ApiException.notFound("Test Domain " + id + " not found"));
        TopologyService.GraphSnapshot graph = topologies.graphVersion(
                row.topologyId(), row.topologyVersion(), null, null, 1000);
        Map<Long, TopologyService.GraphNode> nodes = new LinkedHashMap<>();
        graph.nodes().forEach(node -> nodes.put(node.id(), node));
        List<RelationshipStatement> relationships = graph.edges().stream().map(edge -> {
            TopologyService.GraphNode child = nodes.get(edge.childNodeId());
            TopologyService.GraphNode parent = nodes.get(edge.parentNodeId());
            String childName = child == null ? String.valueOf(edge.childNodeId()) : child.name();
            String parentName = parent == null ? String.valueOf(edge.parentNodeId()) : parent.name();
            String statement = childName + " belongs to " + parentName + " through "
                    + String.join(", ", edge.childColumns()) + ".";
            return new RelationshipStatement(edge.id(), childName, parentName, edge.childColumns(),
                    edge.parentColumns(), edge.evidenceType(), edge.decisionStatus(), statement);
        }).toList();
        return new DomainDetail(summary, assets(id), blueprints(id), relationships,
                parseMap(row.settingsJson()), graph.truncated());
    }

    @Transactional
    public DomainAsset bindAsset(long domainId, AssetRequest request) {
        DomainRow domain = requireDomain(domainId);
        String type = upper(request == null ? null : request.assetType());
        if (!ASSET_TYPES.contains(type)) throw ApiException.bad("Unsupported Test Domain asset type");
        String artifactId = required(request.artifactId(), "Artifact");
        if ("SELF_SERVICE_PRODUCT".equals(type)) {
            product(artifactId);
        }
        String role = upper(firstText(request.assetRole(), "EXECUTION"));
        try {
            jdbc.update("""
                    INSERT INTO scenario_domain_assets
                      (domain_id,asset_type,artifact_id,artifact_version,asset_role,required,config_json)
                    VALUES (?,?,?,?,?,?,?)
                    """, domainId, type, artifactId, request.artifactVersion(), role,
                    !Boolean.FALSE.equals(request.required()), write(nodeMap(request.config())));
        } catch (Exception duplicate) {
            throw ApiException.conflict("That asset is already attached to this Test Domain");
        }
        audit.record(actor(), "TEST_DOMAIN_ASSET_BOUND", "SCENARIO_FABRIC", "test-domain",
                String.valueOf(domainId), domain.name(), "SUCCESS",
                type + ":" + artifactId, null);
        return assets(domainId).stream()
                .filter(asset -> asset.assetType().equals(type) && asset.artifactId().equals(artifactId)
                        && asset.assetRole().equals(role))
                .findFirst().orElseThrow();
    }

    @Transactional
    public void unbindAsset(long domainId, long assetId) {
        DomainRow domain = requireDomain(domainId);
        int changed = jdbc.update("DELETE FROM scenario_domain_assets WHERE id=? AND domain_id=?", assetId, domainId);
        if (changed != 1) throw ApiException.notFound("Test Domain asset not found");
        audit.record(actor(), "TEST_DOMAIN_ASSET_UNBOUND", "SCENARIO_FABRIC", "test-domain",
                String.valueOf(domainId), domain.name(), "SUCCESS", "asset=" + assetId, null);
    }

    public List<DomainAsset> assets(long domainId) {
        requireDomain(domainId);
        return jdbc.query("""
                        SELECT * FROM scenario_domain_assets
                         WHERE domain_id=? ORDER BY asset_role,asset_type,id
                        """, (rs, row) -> new DomainAsset(rs.getLong("id"), rs.getLong("domain_id"),
                        rs.getString("asset_type"), rs.getString("artifact_id"),
                        nullableInteger(rs, "artifact_version"), rs.getString("asset_role"),
                        rs.getBoolean("required"), parseMap(rs.getString("config_json")),
                        instant(rs.getTimestamp("created_at"))), domainId);
    }

    public List<BlueprintView> blueprints(Long domainId) {
        String sql = """
                SELECT b.*,d.name domain_name,d.owner_user_id domain_owner_user_id,
                       d.owner_group_id domain_owner_group_id,d.visibility domain_visibility
                  FROM scenario_blueprints b JOIN scenario_domains d ON d.id=b.domain_id
                """;
        List<Object> args = new ArrayList<>();
        if (domainId != null) {
            sql += " WHERE b.domain_id=?";
            args.add(domainId);
        }
        sql += " ORDER BY b.updated_at DESC,LOWER(b.name)";
        return jdbc.query(sql, (rs, row) -> blueprint(rs), args.toArray()).stream()
                .filter(row -> ownership.canSee(row.domainOwnerUserId(), row.domainOwnerGroupId(),
                        row.domainVisibility()))
                .toList();
    }

    @Transactional
    public BlueprintView createBlueprint(long domainId, BlueprintRequest request) {
        requireDomain(domainId);
        return createBlueprintInternal(domainId, request);
    }

    @Transactional
    public List<BlueprintView> loadValidationExamples(long domainId) {
        DomainRow domain = requireDomain(domainId);
        List<BlueprintRequest> examples = validationExamples();
        Set<String> existingNames = new LinkedHashSet<>();
        blueprints(domainId).forEach(item -> existingNames.add(item.name().toLowerCase(Locale.ROOT)));
        for (BlueprintRequest example : examples) {
            if (existingNames.add(example.name().toLowerCase(Locale.ROOT))) {
                createBlueprintInternal(domainId, example);
            }
        }
        Set<String> exampleNames = examples.stream()
                .map(BlueprintRequest::name)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<BlueprintView> loaded = blueprints(domainId).stream()
                .filter(item -> exampleNames.contains(item.name().toLowerCase(Locale.ROOT)))
                .toList();
        audit.record(actor(), "SCENARIO_VALIDATION_EXAMPLES_LOADED", "SCENARIO_FABRIC",
                "test-domain", String.valueOf(domainId), domain.name(), "SUCCESS",
                "examples=" + loaded.size(), null);
        return loaded;
    }

    @Transactional
    public BlueprintView updateBlueprint(long blueprintId, BlueprintRequest request) {
        BlueprintView current = requireBlueprint(blueprintId);
        String name = validName(request == null ? null : request.name(), "Scenario Blueprint");
        String entityType = required(request.entityType(), "Business object");
        validateBlueprint(request);
        jdbc.update("""
                        UPDATE scenario_blueprints
                           SET name=?,description=?,entity_type=?,status=?,version_no=?,
                               preconditions_json=?,event_json=?,expected_json=?,coverage_json=?,
                               delivery_json=?,questionnaire_json=?,verification_json=?,
                               updated_at=CURRENT_TIMESTAMP
                         WHERE id=?
                        """, name, trim(request.description(), 2000), trim(entityType, 120),
                upper(firstText(request.status(), "PUBLISHED")), current.versionNo() + 1,
                write(nodeArray(request.preconditions())), write(nodeObject(request.event())),
                write(nodeArray(request.expected())), write(nodeObject(request.coverage())),
                write(nodeObject(request.delivery())), write(nodeArray(request.questionnaire())),
                write(nodeObject(request.verification())), blueprintId);
        BlueprintView updated = requireBlueprint(blueprintId);
        insertBlueprintVersion(updated);
        audit.record(actor(), "SCENARIO_BLUEPRINT_UPDATED", "SCENARIO_FABRIC", "scenario-blueprint",
                String.valueOf(blueprintId), updated.name(), "SUCCESS",
                "immutable version=" + updated.versionNo(), null);
        return updated;
    }

    public List<MissionView> missions() {
        return jdbc.query("""
                        SELECT m.*,d.name domain_name,d.owner_group_id domain_owner_group_id,
                               d.visibility domain_visibility,b.name blueprint_name
                          FROM scenario_missions m
                          JOIN scenario_domains d ON d.id=m.domain_id
                          JOIN scenario_blueprints b ON b.id=m.blueprint_id
                         ORDER BY m.created_at DESC
                        """, (rs, row) -> mission(rs))
                .stream()
                .filter(row -> canSeeMission(row))
                .toList();
    }

    @Transactional
    public MissionView createMission(MissionRequest request) {
        if (request == null || request.blueprintId() == null) throw ApiException.bad("Scenario Blueprint is required");
        BlueprintView blueprint = requireBlueprint(request.blueprintId());
        DomainRow domain = requireDomain(blueprint.domainId());
        String title = validMissionTitle(request.title());
        String intent = required(request.intent(), "Test objective");
        if (intent.length() < 20 || intent.length() > 4000) {
            throw ApiException.bad("Test objective must be between 20 and 4000 characters");
        }
        long count = request.requestedCount() == null ? 1 : request.requestedCount();
        if (count < 1 || count > 100_000_000L) throw ApiException.bad("Requested count must be between 1 and 100000000");
        String strategy = upper(firstText(request.sourceStrategy(), "AUTO"));
        if (!STRATEGIES.contains(strategy)) throw ApiException.bad("Unsupported source strategy");
        if (Boolean.TRUE.equals(request.reservationRequested())
                && (request.reservationHours() == null || request.reservationHours() < 1 || request.reservationHours() > 720)) {
            throw ApiException.bad("Reservation must be between 1 and 720 hours");
        }
        Map<String, Object> parameters = request.parameters() == null ? Map.of() : request.parameters();
        validateMissionParameters(blueprint, parameters);

        String id = UUID.randomUUID().toString();
        ProductBinding binding = binding(domain.id(), blueprint.delivery());
        ScenarioCompiler.Compilation compilation = compiler.compile(new ScenarioCompiler.Input(
                id, domain.name(), domain.topologyVersion(), domain.topologyHash(), blueprint.versionNo(),
                blueprint.preconditions(), blueprint.event(), blueprint.expected(), blueprint.coverage(),
                blueprint.delivery(), blueprint.questionnaire(), blueprint.verification(),
                parameters, strategy, count,
                trim(request.targetEnvironment(), 120), binding == null ? null : binding.id(),
                binding == null ? null : binding.type()));
        String status = compilation.executable() ? "PLANNED" : "NEEDS_BINDING";
        AccessPrincipal actor = ownership.require();
        jdbc.update("""
                        INSERT INTO scenario_missions
                          (id,domain_id,blueprint_id,blueprint_version,title,intent_text,target_environment,
                           source_strategy,requested_count,parameters_json,reservation_requested,reservation_hours,
                           status,plan_json,coverage_json,verification_json,ready_pack_json,
                           requested_by_id,requested_by)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, id, domain.id(), blueprint.id(), blueprint.versionNo(), title, intent,
                trim(request.targetEnvironment(), 120), strategy, count,
                write(parameters),
                Boolean.TRUE.equals(request.reservationRequested()), request.reservationHours(), status,
                write(compilation.plan()), write(compilation.coverage()), "{}", "{}",
                actor.userId(), actor.username());
        insertCases(id, compilation.cases());
        event(id, "MISSION_PLANNED", actor.username(),
                compilation.executable() ? "Mission compiled and ready for review" : "Mission needs an execution product",
                Map.of("strategy", compilation.strategy(), "caseCount", compilation.cases().size(),
                        "executable", compilation.executable()));
        audit.record(actor.username(), "TEST_DATA_MISSION_CREATED", "SCENARIO_FABRIC", "mission",
                id, title, "SUCCESS", "cases=" + compilation.cases().size(), null);
        return mission(id);
    }

    public MissionView mission(String id) {
        MissionView view = missions().stream().filter(row -> row.id().equals(id)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Test Data Mission not found"));
        return view.withDetails(cases(id), events(id));
    }

    @Transactional
    public MissionView launch(String id) {
        MissionView mission = mission(id);
        assertMissionOwner(mission);
        if ("NEEDS_BINDING".equals(mission.status())) {
            throw ApiException.bad("Bind an approved self-service product to the Test Domain before launch");
        }
        if (Set.of("READY", "READY_WITH_WARNINGS", "RUNNING").contains(mission.status())) {
            throw ApiException.bad("This Mission has already launched");
        }

        if (mission.selfServiceOrderId() != null) {
            Map<String, Object> order = selfService.get(mission.selfServiceOrderId());
            String orderStatus = String.valueOf(order.get("status"));
            if ("APPROVED".equals(orderStatus)) {
                selfService.fulfill(mission.selfServiceOrderId());
                jdbc.update("UPDATE scenario_missions SET status='RUNNING',launched_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=?", id);
                event(id, "EXECUTION_LAUNCHED", actor(), "Approved execution launched",
                        Map.of("orderId", mission.selfServiceOrderId()));
            } else if ("PENDING_APPROVAL".equals(orderStatus)) {
                throw ApiException.bad("This Mission is waiting for maker-checker approval");
            }
            return refresh(id);
        }

        Map<String, Object> plan = mission.plan();
        String productId = Objects.toString(plan.get("productId"), null);
        if (productId == null) throw ApiException.bad("Mission plan has no execution product");
        Map<String, Object> parameters = new LinkedHashMap<>(mission.parameters());
        parameters.put("_scenarioMissionId", mission.id());
        parameters.put("_scenarioCaseCount", mission.coverage().get("caseCount"));
        parameters.put("_scenarioStrategy", plan.get("strategy"));
        Map<String, Object> created = selfService.request(new EnterpriseSelfServiceService.OrderRequest(
                productId, mission.intent(), "SCENARIO", mission.targetEnvironment(), parameters,
                mission.requestedCount(), coverageLabel(mission.coverage()),
                deliveryMode(Objects.toString(plan.get("productType"), "")),
                mission.reservationRequested(), mission.reservationHours(), null));
        String orderId = String.valueOf(created.get("id"));
        String orderStatus = String.valueOf(created.get("status"));
        jdbc.update("""
                UPDATE scenario_missions
                   SET self_service_order_id=?,status=?,launched_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                 WHERE id=?
                """, orderId, "APPROVED".equals(orderStatus) ? "RUNNING" : "WAITING_APPROVAL", id);
        event(id, "SELF_SERVICE_REQUEST_CREATED", actor(),
                "APPROVED".equals(orderStatus) ? "Execution approved and launching" : "Maker-checker approval required",
                Map.of("orderId", orderId, "orderStatus", orderStatus));
        if ("APPROVED".equals(orderStatus)) {
            selfService.fulfill(orderId);
            event(id, "EXECUTION_LAUNCHED", actor(), "Governed execution submitted", Map.of("orderId", orderId));
        }
        return refresh(id);
    }

    @Transactional
    public MissionView refresh(String id) {
        MissionView mission = mission(id);
        if (mission.selfServiceOrderId() == null) return mission;
        Map<String, Object> order = selfService.get(mission.selfServiceOrderId());
        String orderStatus = String.valueOf(order.get("status"));
        if ("PENDING_APPROVAL".equals(orderStatus)) {
            updateMissionStatus(id, "WAITING_APPROVAL", Map.of("orderStatus", orderStatus));
            return mission(id);
        }
        if ("APPROVED".equals(orderStatus)) {
            updateMissionStatus(id, "APPROVED", Map.of("orderStatus", orderStatus));
            return mission(id);
        }
        Map<String, Object> execution = selfService.executionStatus(mission.selfServiceOrderId());
        String executionStatus = String.valueOf(execution.get("status"));
        if (Set.of("SUBMITTED", "RUNNING").contains(executionStatus)) {
            updateMissionStatus(id, "RUNNING", execution);
        } else if (Set.of("FAILED", "CANCELED").contains(executionStatus)) {
            Map<String, Object> verification = verify(mission, execution, false);
            jdbc.update("""
                    UPDATE scenario_missions
                       SET status=?,verification_json=?,completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                     WHERE id=?
                    """, executionStatus, write(verification), id);
            updateCases(id, "FAILED", execution);
            event(id, "MISSION_" + executionStatus, "SYSTEM",
                    Objects.toString(execution.get("message"), "Execution did not complete"), execution);
        } else if ("COMPLETED".equals(executionStatus)) {
            Map<String, Object> verification = verify(mission, execution, true);
            boolean warning = Boolean.TRUE.equals(verification.get("hasWarnings"));
            String status = warning ? "READY_WITH_WARNINGS" : "READY";
            Map<String, Object> pack = readyPack(mission, execution, verification, status);
            jdbc.update("""
                    UPDATE scenario_missions
                       SET status=?,verification_json=?,ready_pack_json=?,
                           completed_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP
                     WHERE id=?
                    """, status, write(verification), write(pack), id);
            updateCases(id, "READY", execution);
            event(id, "READY_TO_TEST", "SYSTEM", "Mission delivery completed and verification evidence was retained",
                    Map.of("status", status, "runRef", Objects.toString(execution.get("runRef"), "")));
        }
        return mission(id);
    }

    private BlueprintView createBlueprintInternal(long domainId, BlueprintRequest request) {
        String name = validName(request == null ? null : request.name(), "Scenario Blueprint");
        String entityType = required(request.entityType(), "Business object");
        validateBlueprint(request);
        boolean duplicate = blueprints(domainId).stream().anyMatch(row -> row.name().equalsIgnoreCase(name));
        if (duplicate) throw ApiException.conflict("A Scenario Blueprint with that name already exists in this Test Domain");
        long id = insert("""
                        INSERT INTO scenario_blueprints
                          (domain_id,name,description,entity_type,status,preconditions_json,event_json,
                           expected_json,coverage_json,delivery_json,questionnaire_json,verification_json,
                           owner_user_id,owner_username)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                        """, domainId, name, trim(request.description(), 2000), trim(entityType, 120),
                upper(firstText(request.status(), "PUBLISHED")),
                write(nodeArray(request.preconditions())), write(nodeObject(request.event())),
                write(nodeArray(request.expected())), write(nodeObject(request.coverage())),
                write(nodeObject(request.delivery())), write(nodeArray(request.questionnaire())),
                write(nodeObject(request.verification())), ownership.defaultOwnerUserId(), actor());
        BlueprintView created = requireBlueprint(id);
        insertBlueprintVersion(created);
        audit.record(actor(), "SCENARIO_BLUEPRINT_CREATED", "SCENARIO_FABRIC", "scenario-blueprint",
                String.valueOf(id), name, "SUCCESS", "domain=" + domainId + " version=1", null);
        return created;
    }

    private BlueprintRequest starterBlueprint(String domainName, String businessDomain) {
        return new BlueprintRequest("Baseline " + domainName,
                "Starter Blueprint generated from the published topology. Review business conditions and bind an execution product before launch.",
                firstText(businessDomain, "Business entity"), "PUBLISHED",
                readTree("""
                        [{"field":"entity.status","operator":"EQUALS","value":"ACTIVE","required":true},
                         {"field":"entity.recordCount","operator":"GREATER_THAN_OR_EQUAL","value":1,"required":true}]
                        """),
                readTree("""
                        {"action":"provision test-ready entity state","parameters":{}}
                        """),
                readTree("""
                        [{"field":"delivery.status","operator":"EQUALS","value":"READY_TO_TEST"}]
                        """),
                readTree("""
                        {"techniques":["BASELINE","BOUNDARY","NEGATIVE","STATE_TRANSITION","PAIRWISE"],
                         "boundaries":[{"field":"entity.recordCount","value":1}],
                         "parameters":{"customerTier":["STANDARD","PREMIUM"],"channel":["WEB","MOBILE","BRANCH"]}}
                        """),
                readTree("""
                        {"defaultStrategy":"AUTO","systems":[]}
                        """),
                readTree("""
                        [{"key":"customerTier","label":"Customer tier","type":"SELECT","required":true,
                          "options":["STANDARD","PREMIUM"]},
                         {"key":"channel","label":"Channel","type":"SELECT","required":true,
                          "options":["WEB","MOBILE","BRANCH"]}]
                        """),
                readTree("""
                        {"checks":["ENGINE_COMPLETED","NO_REJECTS","TOPOLOGY_COMPATIBLE","COVERAGE_RETAINED"],
                         "predicates":[]}
                        """));
    }

    private List<BlueprintRequest> validationExamples() {
        return List.of(
                new BlueprintRequest(
                        "Active Card Purchase Approval",
                        "Prepare an active customer and card with sufficient available credit, then authorize a purchase successfully.",
                        "Card authorization", "PUBLISHED",
                        readTree("""
                                [{"field":"customer.status","operator":"EQUALS","value":"ACTIVE","required":true},
                                 {"field":"card.status","operator":"EQUALS","value":"ACTIVE","required":true},
                                 {"field":"transaction.amount","operator":"GREATER_THAN_OR_EQUAL","value":100,"required":true}]
                                """),
                        readTree("""
                                {"action":"AUTHORIZE_CARD_PURCHASE","parameters":{"currency":"USD"}}
                                """),
                        readTree("""
                                [{"field":"authorization.status","operator":"EQUALS","value":"APPROVED"},
                                 {"field":"ledger.holdStatus","operator":"EQUALS","value":"CREATED"}]
                                """),
                        readTree("""
                                {"techniques":["BASELINE","BOUNDARY","PAIRWISE"],
                                 "boundaries":[{"field":"transaction.amount","value":100}],
                                 "parameters":{"customerTier":["STANDARD","PREMIUM"],
                                               "channel":["WEB","MOBILE","POS"],
                                               "merchantCategory":["GROCERY","TRAVEL","ECOMMERCE"]}}
                                """),
                        readTree("""
                                {"defaultStrategy":"AUTO","systems":["CUSTOMER","CARD","LEDGER"]}
                                """),
                        readTree("""
                                [{"key":"customerTier","label":"Customer tier","type":"SELECT","required":true,
                                  "options":["STANDARD","PREMIUM"],"defaultValue":"STANDARD"},
                                 {"key":"channel","label":"Purchase channel","type":"SELECT","required":true,
                                  "options":["WEB","MOBILE","POS"],"defaultValue":"WEB"},
                                 {"key":"merchantCategory","label":"Merchant category","type":"SELECT","required":true,
                                  "options":["GROCERY","TRAVEL","ECOMMERCE"],"defaultValue":"GROCERY"}]
                                """),
                        readTree("""
                                {"checks":["ENGINE_COMPLETED","NO_REJECTS","TOPOLOGY_COMPATIBLE","COVERAGE_RETAINED"],
                                 "predicates":[{"code":"AUTH_APPROVED","field":"authorization.status",
                                                "operator":"EQUALS","value":"APPROVED"}]}
                                """)),
                new BlueprintRequest(
                        "Credit Limit Decline Boundary",
                        "Create card purchases immediately below, at, and above available credit and prove the decline boundary.",
                        "Card authorization", "PUBLISHED",
                        readTree("""
                                [{"field":"customer.status","operator":"EQUALS","value":"ACTIVE","required":true},
                                 {"field":"card.status","operator":"EQUALS","value":"ACTIVE","required":true},
                                 {"field":"transaction.amount","operator":"GREATER_THAN","value":500,"required":true}]
                                """),
                        readTree("""
                                {"action":"AUTHORIZE_CARD_PURCHASE","parameters":{"expectedDecision":"DECLINED"}}
                                """),
                        readTree("""
                                [{"field":"authorization.status","operator":"EQUALS","value":"DECLINED"},
                                 {"field":"authorization.reason","operator":"EQUALS","value":"CREDIT_LIMIT_EXCEEDED"},
                                 {"field":"ledger.holdStatus","operator":"EQUALS","value":"NOT_CREATED"}]
                                """),
                        readTree("""
                                {"techniques":["BASELINE","BOUNDARY","NEGATIVE","PAIRWISE"],
                                 "boundaries":[{"field":"transaction.amount","value":500}],
                                 "parameters":{"channel":["WEB","MOBILE","POS"],
                                               "cardNetwork":["VISA","MASTERCARD"],
                                               "merchantRisk":["LOW","HIGH"]}}
                                """),
                        readTree("""
                                {"defaultStrategy":"AUTO","systems":["CUSTOMER","CARD","LEDGER"]}
                                """),
                        readTree("""
                                [{"key":"channel","label":"Purchase channel","type":"SELECT","required":true,
                                  "options":["WEB","MOBILE","POS"],"defaultValue":"POS"},
                                 {"key":"cardNetwork","label":"Card network","type":"SELECT","required":true,
                                  "options":["VISA","MASTERCARD"],"defaultValue":"VISA"},
                                 {"key":"merchantRisk","label":"Merchant risk","type":"SELECT","required":true,
                                  "options":["LOW","HIGH"],"defaultValue":"LOW"}]
                                """),
                        readTree("""
                                {"checks":["ENGINE_COMPLETED","NO_REJECTS","TOPOLOGY_COMPATIBLE","COVERAGE_RETAINED"],
                                 "predicates":[{"code":"AUTH_DECLINED","field":"authorization.status",
                                                "operator":"EQUALS","value":"DECLINED"},
                                               {"code":"NO_LEDGER_HOLD","field":"ledger.holdStatus",
                                                "operator":"EQUALS","value":"NOT_CREATED"}]}
                                """)),
                new BlueprintRequest(
                        "Dormant Customer Reactivation",
                        "Prepare a dormant, verified customer and prove controlled reactivation across customer, audit, and channel systems.",
                        "Customer lifecycle", "PUBLISHED",
                        readTree("""
                                [{"field":"customer.status","operator":"EQUALS","value":"DORMANT","required":true},
                                 {"field":"customer.kycStatus","operator":"EQUALS","value":"VERIFIED","required":true}]
                                """),
                        readTree("""
                                {"action":"REACTIVATE_CUSTOMER","parameters":{"requireAudit":true}}
                                """),
                        readTree("""
                                [{"field":"customer.status","operator":"EQUALS","value":"ACTIVE"},
                                 {"field":"audit.eventType","operator":"EQUALS","value":"CUSTOMER_REACTIVATED"}]
                                """),
                        readTree("""
                                {"techniques":["BASELINE","NEGATIVE","STATE_TRANSITION","PAIRWISE"],
                                 "parameters":{"reactivationTrigger":["CUSTOMER_REQUEST","OPERATIONS_REVIEW"],
                                               "channel":["BRANCH","CALL_CENTER","MOBILE"],
                                               "riskBand":["LOW","MEDIUM"]}}
                                """),
                        readTree("""
                                {"defaultStrategy":"AUTO","systems":["CUSTOMER","KYC","AUDIT","CHANNEL"]}
                                """),
                        readTree("""
                                [{"key":"reactivationTrigger","label":"Reactivation trigger","type":"SELECT","required":true,
                                  "options":["CUSTOMER_REQUEST","OPERATIONS_REVIEW"],"defaultValue":"CUSTOMER_REQUEST"},
                                 {"key":"channel","label":"Servicing channel","type":"SELECT","required":true,
                                  "options":["BRANCH","CALL_CENTER","MOBILE"],"defaultValue":"BRANCH"},
                                 {"key":"riskBand","label":"Risk band","type":"SELECT","required":true,
                                  "options":["LOW","MEDIUM"],"defaultValue":"LOW"}]
                                """),
                        readTree("""
                                {"checks":["ENGINE_COMPLETED","NO_REJECTS","TOPOLOGY_COMPATIBLE","COVERAGE_RETAINED"],
                                 "predicates":[{"code":"CUSTOMER_ACTIVE","field":"customer.status",
                                                "operator":"EQUALS","value":"ACTIVE"},
                                               {"code":"AUDIT_RETAINED","field":"audit.eventType",
                                                "operator":"EQUALS","value":"CUSTOMER_REACTIVATED"}]}
                                """))
        );
    }

    private void validateBlueprint(BlueprintRequest request) {
        if (request == null) throw ApiException.bad("Scenario Blueprint is required");
        if (!nodeArray(request.preconditions()).isArray()) throw ApiException.bad("Preconditions must be a list");
        if (!nodeArray(request.expected()).isArray()) throw ApiException.bad("Expected outcomes must be a list");
        JsonNode coverage = nodeObject(request.coverage());
        if (!coverage.path("techniques").isArray() || coverage.path("techniques").isEmpty()) {
            throw ApiException.bad("Select at least one coverage technique");
        }
    }

    private void validateMissionParameters(BlueprintView blueprint, Map<String, Object> parameters) {
        Set<String> allowed = new LinkedHashSet<>();
        if (blueprint.questionnaire().isArray()) {
            for (JsonNode field : blueprint.questionnaire()) {
                String key = clean(field.path("key").asText(null));
                if (key == null) continue;
                allowed.add(key);
                Object value = parameters.get(key);
                boolean missing = value == null || value instanceof String text && text.isBlank();
                if (field.path("required").asBoolean(false) && missing) {
                    throw ApiException.bad("Mission parameter '" + key + "' is required");
                }
                JsonNode options = field.path("options");
                if (!missing && options.isArray() && !options.isEmpty()) {
                    boolean accepted = false;
                    for (JsonNode option : options) {
                        if (Objects.equals(String.valueOf(value), option.asText())) {
                            accepted = true;
                            break;
                        }
                    }
                    if (!accepted) throw ApiException.bad("Mission parameter '" + key + "' is not an allowed option");
                }
            }
        }
        for (String supplied : parameters.keySet()) {
            if (!allowed.contains(supplied)) {
                throw ApiException.bad("Mission parameter '" + supplied + "' is not exposed by this Blueprint");
            }
        }
    }

    private ProductBinding binding(long domainId, JsonNode delivery) {
        String productId = delivery == null ? null : clean(delivery.path("productId").asText(null));
        if (productId != null) {
            Map<String, Object> product = product(productId);
            return new ProductBinding(productId, String.valueOf(product.get("productType")));
        }
        List<DomainAsset> matches = assets(domainId).stream()
                .filter(asset -> "SELF_SERVICE_PRODUCT".equals(asset.assetType())
                        && "EXECUTION".equals(asset.assetRole()))
                .toList();
        if (matches.isEmpty()) return null;
        DomainAsset selected = matches.get(0);
        Map<String, Object> product = product(selected.artifactId());
        return new ProductBinding(selected.artifactId(), String.valueOf(product.get("productType")));
    }

    private Map<String, Object> product(String id) {
        return selfService.catalog(null, null, null).stream()
                .filter(row -> Objects.equals(String.valueOf(row.get("id")), id))
                .findFirst().orElseThrow(() -> ApiException.bad("Approved self-service product " + id + " is not available"));
    }

    private Map<String, Object> verify(MissionView mission, Map<String, Object> execution, boolean completed) {
        DomainRow domain = requireDomain(mission.domainId());
        TopologyService.TopologySummary topology = topologies.get(domain.topologyId());
        long rejected = number(execution.get("rowsRejected"));
        long written = number(execution.get("rowsWritten"));
        boolean topologyCompatible = domain.topologyVersion() == topology.currentVersion()
                && Objects.equals(domain.topologyHash(), topology.currentHash());
        boolean semanticConfigured = mission.blueprint().verification().path("predicates").isArray()
                && !mission.blueprint().verification().path("predicates").isEmpty();
        List<Map<String, Object>> predicateEvidence = mapList(execution.get("predicateChecks"));
        boolean semanticEvaluated = semanticConfigured && !predicateEvidence.isEmpty();
        boolean semanticPassed = semanticEvaluated
                && predicateEvidence.stream().allMatch(item -> Boolean.TRUE.equals(item.get("passed")));
        List<Map<String, Object>> checks = new ArrayList<>();
        checks.add(check("ENGINE_COMPLETED", completed, Objects.toString(execution.get("status"), "")));
        checks.add(check("NO_REJECTS", rejected == 0, rejected + " rejected row(s)"));
        checks.add(check("TOPOLOGY_COMPATIBLE", topologyCompatible,
                "domain v" + domain.topologyVersion() + ", current v" + topology.currentVersion()));
        checks.add(check("COVERAGE_RETAINED", !cases(mission.id()).isEmpty(),
                cases(mission.id()).size() + " scenario case(s) retained"));
        if (mission.requestedCount() > 0 && written > 0) {
            checks.add(check("REQUESTED_VOLUME", written >= mission.requestedCount(),
                    written + " of " + mission.requestedCount() + " requested row(s) reported"));
        }
        checks.add(check("SCENARIO_PREDICATES", semanticPassed,
                !semanticConfigured
                        ? "No target predicate query is configured"
                        : semanticEvaluated
                            ? predicateEvidence.size() + " target predicate(s) evaluated"
                            : "Configured predicates retained, but the delivery engine returned no predicate evidence"));
        boolean failed = checks.stream().anyMatch(row -> Boolean.FALSE.equals(row.get("passed"))
                && !"SCENARIO_PREDICATES".equals(row.get("code")));
        if (semanticEvaluated && !semanticPassed) failed = true;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", failed ? "FAILED" : semanticPassed ? "VERIFIED" : "VERIFIED_WITH_WARNINGS");
        out.put("checks", checks);
        out.put("predicateEvidence", predicateEvidence);
        out.put("hasWarnings", !semanticPassed);
        out.put("rowsWritten", written);
        out.put("rowsRejected", rejected);
        out.put("verifiedAt", Instant.now().toString());
        return out;
    }

    private Map<String, Object> readyPack(MissionView mission, Map<String, Object> execution,
                                           Map<String, Object> verification, String status) {
        List<Map<String, Object>> handles = cases(mission.id()).stream().map(item -> {
            Map<String, Object> handle = new LinkedHashMap<>();
            handle.put("caseKey", item.caseKey());
            handle.put("scenario", item.title());
            handle.put("kind", item.caseKind());
            handle.put("expected", item.expected());
            return handle;
        }).toList();
        Map<String, Object> lifecycle = new LinkedHashMap<>();
        lifecycle.put("reserved", mission.reservationRequested());
        lifecycle.put("reservationHours", mission.reservationHours());
        lifecycle.put("resetAvailable", Set.of("VDB_PROVISION", "VDB_REFRESH", "VDB_ROLLBACK")
                .contains(Objects.toString(mission.plan().get("productType"), "")));
        lifecycle.put("selfServiceOrderId", mission.selfServiceOrderId());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("missionId", mission.id());
        out.put("title", mission.title());
        out.put("status", status);
        out.put("domain", mission.domainName());
        out.put("blueprint", mission.blueprintName());
        out.put("blueprintVersion", mission.blueprintVersion());
        out.put("targetEnvironment", mission.targetEnvironment());
        out.put("strategy", mission.plan().get("strategy"));
        out.put("runType", execution.get("runType"));
        out.put("runRef", execution.get("runRef"));
        out.put("rowsWritten", execution.get("rowsWritten"));
        out.put("scenarioHandles", handles);
        out.put("verification", verification);
        out.put("lifecycle", lifecycle);
        out.put("lineage", Map.of(
                "topologyVersion", mission.plan().get("topologyVersion"),
                "topologyHash", mission.plan().get("topologyHash"),
                "productId", mission.plan().get("productId")));
        out.put("readyAt", Instant.now().toString());
        return out;
    }

    private List<DomainAsset> assetsUnchecked(long domainId) {
        return jdbc.query("SELECT * FROM scenario_domain_assets WHERE domain_id=? ORDER BY id",
                (rs, row) -> new DomainAsset(rs.getLong("id"), rs.getLong("domain_id"),
                        rs.getString("asset_type"), rs.getString("artifact_id"),
                        nullableInteger(rs, "artifact_version"), rs.getString("asset_role"),
                        rs.getBoolean("required"), parseMap(rs.getString("config_json")),
                        instant(rs.getTimestamp("created_at"))), domainId);
    }

    private BlueprintView requireBlueprint(long id) {
        List<BlueprintView> rows = jdbc.query("""
                        SELECT b.*,d.name domain_name,d.owner_user_id domain_owner_user_id,
                               d.owner_group_id domain_owner_group_id,d.visibility domain_visibility
                          FROM scenario_blueprints b JOIN scenario_domains d ON d.id=b.domain_id
                         WHERE b.id=?
                        """, (rs, row) -> blueprint(rs), id);
        if (rows.isEmpty()) throw ApiException.notFound("Scenario Blueprint " + id + " not found");
        BlueprintView row = rows.get(0);
        ownership.assertCanSee("scenario blueprint", id, row.domainOwnerUserId(),
                row.domainOwnerGroupId(), row.domainVisibility());
        return row;
    }

    private DomainRow requireDomain(long id) {
        List<DomainRow> rows = jdbc.query("SELECT * FROM scenario_domains WHERE id=?",
                (rs, row) -> new DomainRow(rs.getLong("id"), rs.getLong("topology_id"),
                        rs.getInt("topology_version"), rs.getString("topology_hash"),
                        rs.getString("name"), rs.getString("business_domain"),
                        rs.getString("description"), rs.getString("status"), rs.getInt("version_no"),
                        rs.getString("settings_json"), nullableLong(rs, "owner_user_id"),
                        rs.getString("owner_username"), nullableLong(rs, "owner_group_id"),
                        rs.getString("visibility"), instant(rs.getTimestamp("created_at")),
                        instant(rs.getTimestamp("updated_at"))), id);
        if (rows.isEmpty()) throw ApiException.notFound("Test Domain " + id + " not found");
        DomainRow row = rows.get(0);
        ownership.assertCanSee("test domain", id, row.ownerUserId(), row.ownerGroupId(), row.visibility());
        return row;
    }

    private DomainSummary domainSummary(ResultSet rs) throws SQLException {
        return new DomainSummary(rs.getLong("id"), rs.getLong("topology_id"),
                rs.getInt("topology_version"), rs.getString("topology_hash"),
                rs.getString("name"), rs.getString("business_domain"), rs.getString("description"),
                rs.getString("status"), rs.getInt("version_no"), rs.getInt("blueprint_count"),
                rs.getInt("asset_count"), rs.getInt("mission_count"), rs.getString("owner_username"),
                rs.getString("visibility"), nullableLong(rs, "owner_user_id"),
                nullableLong(rs, "owner_group_id"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("updated_at")));
    }

    private BlueprintView blueprint(ResultSet rs) throws SQLException {
        return new BlueprintView(rs.getLong("id"), rs.getLong("domain_id"), rs.getString("domain_name"),
                rs.getString("name"), rs.getString("description"), rs.getString("entity_type"),
                rs.getString("status"), rs.getInt("version_no"),
                parseTree(rs.getString("preconditions_json")), parseTree(rs.getString("event_json")),
                parseTree(rs.getString("expected_json")), parseTree(rs.getString("coverage_json")),
                parseTree(rs.getString("delivery_json")), parseTree(rs.getString("questionnaire_json")),
                parseTree(rs.getString("verification_json")), rs.getString("owner_username"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                nullableLong(rs, "domain_owner_user_id"), nullableLong(rs, "domain_owner_group_id"),
                rs.getString("domain_visibility"));
    }

    private MissionView mission(ResultSet rs) throws SQLException {
        BlueprintView blueprint = requireBlueprintVersion(
                rs.getLong("blueprint_id"), rs.getInt("blueprint_version"));
        return new MissionView(rs.getString("id"), rs.getLong("domain_id"), rs.getString("domain_name"),
                rs.getLong("blueprint_id"), blueprint.name(), rs.getInt("blueprint_version"),
                rs.getString("title"), rs.getString("intent_text"), rs.getString("target_environment"),
                rs.getString("source_strategy"), rs.getLong("requested_count"),
                parseMap(rs.getString("parameters_json")), rs.getBoolean("reservation_requested"),
                nullableInteger(rs, "reservation_hours"), rs.getString("status"),
                parseMap(rs.getString("plan_json")), parseMap(rs.getString("coverage_json")),
                parseMap(rs.getString("verification_json")), parseMap(rs.getString("ready_pack_json")),
                rs.getString("self_service_order_id"), nullableLong(rs, "requested_by_id"),
                rs.getString("requested_by"), instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("launched_at")), instant(rs.getTimestamp("completed_at")),
                instant(rs.getTimestamp("updated_at")), nullableLong(rs, "domain_owner_group_id"),
                rs.getString("domain_visibility"), blueprint, List.of(), List.of());
    }

    private boolean canSeeMission(MissionView mission) {
        return ownership.canSee(mission.requestedById(), mission.domainOwnerGroupId(), mission.domainVisibility());
    }

    private void assertMissionOwner(MissionView mission) {
        AccessPrincipal caller = ownership.require();
        if (!caller.isAdmin() && !Objects.equals(caller.userId(), mission.requestedById())) {
            throw ApiException.forbidden("Only the Mission requester or an administrator can launch it");
        }
    }

    private List<MissionCase> cases(String missionId) {
        return jdbc.query("""
                        SELECT * FROM scenario_mission_cases
                         WHERE mission_id=? ORDER BY ordinal_no
                        """, (rs, row) -> new MissionCase(rs.getLong("id"), rs.getString("mission_id"),
                        rs.getInt("ordinal_no"), rs.getString("case_key"), rs.getString("title"),
                        rs.getString("case_kind"), parseMap(rs.getString("inputs_json")),
                        parseValue(rs.getString("expected_json")), rs.getString("status"),
                        parseMap(rs.getString("evidence_json"))), missionId);
    }

    private List<MissionEvent> events(String missionId) {
        return jdbc.query("""
                        SELECT * FROM scenario_mission_events
                         WHERE mission_id=? ORDER BY created_at,id
                        """, (rs, row) -> new MissionEvent(rs.getLong("id"), rs.getString("event_type"),
                        rs.getString("actor"), rs.getString("message"),
                        parseMap(rs.getString("detail_json")), instant(rs.getTimestamp("created_at"))),
                missionId);
    }

    private void insertCases(String missionId, List<ScenarioCompiler.CaseSpec> cases) {
        int ordinal = 1;
        for (ScenarioCompiler.CaseSpec item : cases) {
            jdbc.update("""
                    INSERT INTO scenario_mission_cases
                      (mission_id,ordinal_no,case_key,title,case_kind,inputs_json,expected_json)
                    VALUES (?,?,?,?,?,?,?)
                    """, missionId, ordinal++, item.key(), item.title(), item.kind(),
                    write(item.inputs()), write(item.expected()));
        }
    }

    private void insertBlueprintVersion(BlueprintView blueprint) {
        jdbc.update("""
                INSERT INTO scenario_blueprint_versions
                  (blueprint_id,domain_id,version_no,name,description,entity_type,status,
                   preconditions_json,event_json,expected_json,coverage_json,delivery_json,
                   questionnaire_json,verification_json,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, blueprint.id(), blueprint.domainId(), blueprint.versionNo(), blueprint.name(),
                blueprint.description(), blueprint.entityType(), blueprint.status(),
                write(blueprint.preconditions()), write(blueprint.event()), write(blueprint.expected()),
                write(blueprint.coverage()), write(blueprint.delivery()), write(blueprint.questionnaire()),
                write(blueprint.verification()), actor());
    }

    private BlueprintView requireBlueprintVersion(long blueprintId, int versionNo) {
        List<BlueprintView> versions = jdbc.query("""
                        SELECT v.blueprint_id id,v.domain_id,v.name,v.description,v.entity_type,
                               v.status,v.version_no,v.preconditions_json,v.event_json,
                               v.expected_json,v.coverage_json,v.delivery_json,v.questionnaire_json,
                               v.verification_json,v.created_by owner_username,
                               v.created_at,v.created_at updated_at,d.name domain_name,
                               d.owner_user_id domain_owner_user_id,
                               d.owner_group_id domain_owner_group_id,d.visibility domain_visibility
                          FROM scenario_blueprint_versions v
                          JOIN scenario_domains d ON d.id=v.domain_id
                         WHERE v.blueprint_id=? AND v.version_no=?
                        """, (rs, row) -> blueprint(rs), blueprintId, versionNo);
        if (versions.isEmpty()) {
            BlueprintView current = requireBlueprint(blueprintId);
            if (current.versionNo() == versionNo) return current;
            throw ApiException.notFound("Scenario Blueprint version " + versionNo + " not found");
        }
        BlueprintView version = versions.get(0);
        ownership.assertCanSee("scenario blueprint", blueprintId, version.domainOwnerUserId(),
                version.domainOwnerGroupId(), version.domainVisibility());
        return version;
    }

    private void updateCases(String missionId, String status, Map<String, Object> evidence) {
        jdbc.update("""
                UPDATE scenario_mission_cases
                   SET status=?,evidence_json=?
                 WHERE mission_id=?
                """, status, write(Map.of(
                "runType", Objects.toString(evidence.get("runType"), ""),
                "runRef", Objects.toString(evidence.get("runRef"), ""),
                "executionStatus", Objects.toString(evidence.get("status"), ""))), missionId);
    }

    private void updateMissionStatus(String id, String status, Map<String, Object> detail) {
        String previous = jdbc.queryForObject("SELECT status FROM scenario_missions WHERE id=?", String.class, id);
        jdbc.update("UPDATE scenario_missions SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?", status, id);
        if (!Objects.equals(previous, status)) {
            event(id, "MISSION_" + status, "SYSTEM",
                    Objects.toString(detail.get("message"), "Mission status changed to " + status), detail);
        }
    }

    private void event(String missionId, String type, String eventActor,
                       String message, Map<String, Object> detail) {
        jdbc.update("""
                INSERT INTO scenario_mission_events(mission_id,event_type,actor,message,detail_json)
                VALUES (?,?,?,?,?)
                """, missionId, type, eventActor, trim(message, 2000), write(detail));
    }

    private void ensureDomainNameAvailable(String name) {
        if (domains().stream().anyMatch(row -> row.name().equalsIgnoreCase(name))) {
            throw ApiException.conflict("A visible Test Domain named '" + name + "' already exists");
        }
    }

    private long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, key);
        Number value = key.getKey();
        if (value == null) throw new IllegalStateException("Database did not return a generated identifier");
        return value.longValue();
    }

    private String actor() {
        return ownership.caller().map(AccessPrincipal::username).orElse("system");
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(value);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private JsonNode parseTree(String value) {
        if (value == null || value.isBlank()) return json.createObjectNode();
        try {
            return json.readTree(value);
        } catch (Exception ignored) {
            return json.createObjectNode();
        }
    }

    private JsonNode nodeObject(JsonNode value) {
        return value == null || value.isNull() ? json.createObjectNode() : value;
    }

    private JsonNode nodeArray(JsonNode value) {
        return value == null || value.isNull() ? json.createArrayNode() : value;
    }

    private Map<String, Object> nodeMap(JsonNode value) {
        if (value == null || !value.isObject()) return Map.of();
        return json.convertValue(value, new TypeReference<>() {});
    }

    private Map<String, Object> parseMap(String value) {
        if (value == null || value.isBlank()) return new LinkedHashMap<>();
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (!(value instanceof Iterable<?> iterable)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : iterable) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((key, entry) -> row.put(String.valueOf(key), entry));
                rows.add(row);
            }
        }
        return rows;
    }

    private Object parseValue(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return json.readValue(value, Object.class);
        } catch (Exception ignored) {
            return value;
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception failure) {
            throw ApiException.bad("Scenario definition could not be serialized");
        }
    }

    private static Map<String, Object> check(String code, boolean passed, String evidence) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("passed", passed);
        out.put("evidence", evidence);
        return out;
    }

    private static String coverageLabel(Map<String, Object> coverage) {
        Object values = coverage.get("techniques");
        return values instanceof Iterable<?> iterable
                ? String.join(", ", stream(iterable)) : Objects.toString(values, "Scenario coverage");
    }

    private static List<String> stream(Iterable<?> values) {
        List<String> out = new ArrayList<>();
        values.forEach(value -> out.add(Objects.toString(value)));
        return out;
    }

    private static String deliveryMode(String productType) {
        return switch (productType) {
            case "MAPPING" -> "PIPELINE";
            case "VDB_PROVISION", "VDB_REFRESH", "VDB_ROLLBACK" -> "VIRTUAL_DATABASE";
            case "RESERVATION" -> "RESERVATION";
            default -> "DATABASE";
        };
    }

    private static String validName(String value, String label) {
        String name = required(value, label + " name");
        if (name.length() < 8 || name.length() > 120) {
            throw ApiException.bad(label + " name must be between 8 and 120 characters");
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]*")) {
            throw ApiException.bad(label + " name may contain letters, numbers, spaces, dots, underscores and hyphens");
        }
        return name;
    }

    private static String validMissionTitle(String value) {
        String title = required(value, "Mission title");
        if (title.length() < 8 || title.length() > 160) {
            throw ApiException.bad("Mission title must be between 8 and 160 characters");
        }
        return title;
    }

    private static String visibility(String value) {
        String normalized = upper(firstText(value, OwnershipGuard.GROUP));
        if (!VISIBILITIES.contains(normalized)) throw ApiException.bad("Visibility must be PRIVATE, GROUP or SHARED");
        return normalized;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw ApiException.bad(label + " is required");
        return value.trim();
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw ApiException.bad("Value exceeds maximum length " + max);
        return result;
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String firstText(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return null;
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return value == null ? 0 : Long.parseLong(String.valueOf(value));
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String name) throws SQLException {
        long value = rs.getLong(name);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String name) throws SQLException {
        int value = rs.getInt(name);
        return rs.wasNull() ? null : value;
    }

    private record DomainRow(long id, long topologyId, int topologyVersion, String topologyHash,
                             String name, String businessDomain, String description, String status,
                             int versionNo, String settingsJson, Long ownerUserId, String ownerUsername,
                             Long ownerGroupId, String visibility, Instant createdAt, Instant updatedAt) {}

    private record ProductBinding(String id, String type) {}

    public record PublishDomain(Long topologyId, String name, String businessDomain, String description,
                                String visibility, Boolean createStarterBlueprint) {}

    public record AssetRequest(String assetType, String artifactId, Integer artifactVersion,
                               String assetRole, Boolean required, JsonNode config) {}

    public record BlueprintRequest(String name, String description, String entityType, String status,
                                   JsonNode preconditions, JsonNode event, JsonNode expected,
                                   JsonNode coverage, JsonNode delivery, JsonNode questionnaire,
                                   JsonNode verification) {}

    public record MissionRequest(Long blueprintId, String title, String intent, String targetEnvironment,
                                 String sourceStrategy, Long requestedCount, Map<String, Object> parameters,
                                 Boolean reservationRequested, Integer reservationHours) {}

    public record DomainSummary(long id, long topologyId, int topologyVersion, String topologyHash,
                                String name, String businessDomain, String description, String status,
                                int versionNo, int blueprintCount, int assetCount, int missionCount,
                                String ownerUsername, String visibility, Long ownerUserId,
                                Long ownerGroupId, Instant createdAt, Instant updatedAt) {}

    public record DomainDetail(DomainSummary summary, List<DomainAsset> assets,
                               List<BlueprintView> blueprints,
                               List<RelationshipStatement> relationships,
                               Map<String, Object> settings, boolean graphTruncated) {}

    public record DomainAsset(long id, long domainId, String assetType, String artifactId,
                              Integer artifactVersion, String assetRole, boolean required,
                              Map<String, Object> config, Instant createdAt) {}

    public record RelationshipStatement(long edgeId, String child, String parent,
                                        List<String> childColumns, List<String> parentColumns,
                                        String evidenceType, String decisionStatus, String statement) {}

    public record BlueprintView(long id, long domainId, String domainName, String name,
                                String description, String entityType, String status, int versionNo,
                                JsonNode preconditions, JsonNode event, JsonNode expected,
                                JsonNode coverage, JsonNode delivery, JsonNode questionnaire,
                                JsonNode verification, String ownerUsername, Instant createdAt,
                                Instant updatedAt, Long domainOwnerUserId, Long domainOwnerGroupId,
                                String domainVisibility) {}

    public record MissionCase(long id, String missionId, int ordinal, String caseKey, String title,
                              String caseKind, Map<String, Object> inputs, Object expected,
                              String status, Map<String, Object> evidence) {}

    public record MissionEvent(long id, String eventType, String actor, String message,
                               Map<String, Object> detail, Instant createdAt) {}

    public record MissionView(String id, long domainId, String domainName, long blueprintId,
                              String blueprintName, int blueprintVersion, String title, String intent,
                              String targetEnvironment, String sourceStrategy, long requestedCount,
                              Map<String, Object> parameters, boolean reservationRequested,
                              Integer reservationHours, String status, Map<String, Object> plan,
                              Map<String, Object> coverage, Map<String, Object> verification,
                              Map<String, Object> readyPack, String selfServiceOrderId,
                              Long requestedById, String requestedBy, Instant createdAt,
                              Instant launchedAt, Instant completedAt, Instant updatedAt,
                              Long domainOwnerGroupId, String domainVisibility,
                              BlueprintView blueprint, List<MissionCase> cases,
                              List<MissionEvent> events) {
        MissionView withDetails(List<MissionCase> detailCases, List<MissionEvent> detailEvents) {
            return new MissionView(id, domainId, domainName, blueprintId, blueprintName,
                    blueprintVersion, title, intent, targetEnvironment, sourceStrategy,
                    requestedCount, parameters, reservationRequested, reservationHours, status,
                    plan, coverage, verification, readyPack, selfServiceOrderId, requestedById,
                    requestedBy, createdAt, launchedAt, completedAt, updatedAt,
                    domainOwnerGroupId, domainVisibility, blueprint, detailCases, detailEvents);
        }
    }
}
