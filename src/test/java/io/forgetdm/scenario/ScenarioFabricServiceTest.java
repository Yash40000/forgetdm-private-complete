package io.forgetdm.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.automation.EnterpriseSelfServiceService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.topology.TopologyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScenarioFabricServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private ScenarioFabricService service;
    private AccessPrincipal architect;

    @BeforeEach
    void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:scenario_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE topology_models(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE forge_users(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE forge_groups(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE self_service_orders(id VARCHAR(36) PRIMARY KEY)");
        jdbc.update("INSERT INTO topology_models(id) VALUES (77)");
        jdbc.update("INSERT INTO forge_users(id) VALUES (1)");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V76__scenario_fabric.sql")).execute(source);

        AuditService audit = mock(AuditService.class);
        TopologyService topologies = mock(TopologyService.class);
        EnterpriseSelfServiceService selfService = mock(EnterpriseSelfServiceService.class);
        when(topologies.get(77L)).thenReturn(new TopologyService.TopologySummary(
                77L, "Retail Cards", "Cards", "Card authorization systems", "READY",
                "topology-hash-77", 3, 1L, 2, 2, 1, "architect", "GROUP",
                Instant.now(), Instant.now(), 1L, null));
        when(topologies.graphVersion(77L, 3, null, null, 1000)).thenReturn(new TopologyService.GraphSnapshot(
                List.of(
                        new TopologyService.GraphNode(10L, 1L, "Cards", "cards", "card_accounts",
                                "TABLE", 8, 1, 1000L),
                        new TopologyService.GraphNode(11L, 1L, "Cards", "cards", "card_customers",
                                "TABLE", 6, 1, 500L)),
                List.of(new TopologyService.GraphEdge(20L, "fk_card_customer", 10L, 11L,
                        List.of("customer_id"), List.of("customer_id"), "DB_CATALOG",
                        "VERIFIED", 100, true, "{}")),
                2, 1, false));
        when(selfService.catalog(null, null, null)).thenReturn(List.of(Map.of(
                "id", "product-1",
                "label", "Cards safe synthetic",
                "productType", "SYNTHETIC",
                "status", "PUBLISHED")));

        OwnershipGuard ownership = new OwnershipGuard(audit);
        service = new ScenarioFabricService(jdbc, json, ownership, topologies, selfService,
                new ScenarioCompiler(json), audit);
        architect = new AccessPrincipal(1L, "architect", "Architect",
                Set.of("TDM_ARCHITECT"), Set.of("scenario.read", "scenario.manage", "scenario.run"));
    }

    @Test
    void publishesDomainCompilesMissionAndRetainsPinnedBlueprintVersion() {
        ScenarioFabricService.DomainDetail domain = asArchitect(() -> service.publish(
                new ScenarioFabricService.PublishDomain(77L, "Retail Cards Domain", "Cards",
                        "Test-ready card authorization states", "GROUP", true)));

        assertEquals(1, domain.relationships().size());
        assertTrue(domain.relationships().get(0).statement().contains("belongs to"));
        assertEquals(1, domain.blueprints().size());

        asArchitect(() -> service.bindAsset(domain.summary().id(),
                new ScenarioFabricService.AssetRequest("SELF_SERVICE_PRODUCT", "product-1",
                        1, "EXECUTION", true, json.createObjectNode())));

        ScenarioFabricService.BlueprintView versionOne = domain.blueprints().get(0);
        ScenarioFabricService.MissionView mission = asArchitect(() -> service.createMission(
                new ScenarioFabricService.MissionRequest(versionOne.id(),
                        "Premium mobile authorization", "Prove active premium mobile card authorization behavior",
                        "QA", "AUTO", 250L,
                        Map.of("customerTier", "PREMIUM", "channel", "MOBILE"),
                        true, 24)));

        assertEquals("PLANNED", mission.status());
        assertEquals("product-1", mission.plan().get("productId"));
        assertTrue(mission.cases().size() >= 5);
        assertFalse(mission.events().isEmpty());

        ScenarioFabricService.BlueprintView versionTwo = asArchitect(() -> service.updateBlueprint(
                versionOne.id(), new ScenarioFabricService.BlueprintRequest(
                        versionOne.name(), versionOne.description(), versionOne.entityType(), "PUBLISHED",
                        json.valueToTree(List.of(Map.of("field", "entity.status", "operator", "EQUALS",
                                "value", "BLOCKED", "required", true))),
                        versionOne.event(), versionOne.expected(), versionOne.coverage(),
                        versionOne.delivery(), versionOne.questionnaire(), versionOne.verification())));
        assertEquals(2, versionTwo.versionNo());
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_blueprint_versions WHERE blueprint_id=?",
                Integer.class, versionOne.id()));

        ScenarioFabricService.MissionView reloaded = asArchitect(() -> service.mission(mission.id()));
        assertEquals(1, reloaded.blueprintVersion());
        assertEquals("ACTIVE", reloaded.blueprint().preconditions().get(0).path("value").asText());
    }

    @Test
    void rejectsParametersNotExposedByBlueprintQuestionnaire() {
        ScenarioFabricService.DomainDetail domain = asArchitect(() -> service.publish(
                new ScenarioFabricService.PublishDomain(77L, "Retail Cards Domain", "Cards",
                        "Test-ready card authorization states", "GROUP", true)));
        ScenarioFabricService.BlueprintView blueprint = domain.blueprints().get(0);

        ApiException failure = assertThrows(ApiException.class, () -> asArchitect(() ->
                service.createMission(new ScenarioFabricService.MissionRequest(
                        blueprint.id(), "Unsafe hidden override",
                        "Attempt to pass a parameter that the product owner did not expose",
                        "QA", "AUTO", 10L,
                        Map.of("customerTier", "STANDARD", "channel", "WEB", "adminOverride", true),
                        false, null))));

        assertTrue(failure.getMessage().contains("not exposed"));
    }

    @Test
    void loadsRunnableValidationExamplesWithoutDuplicates() {
        ScenarioFabricService.DomainDetail domain = asArchitect(() -> service.publish(
                new ScenarioFabricService.PublishDomain(77L, "Retail Cards Domain", "Cards",
                        "Test-ready card authorization states", "GROUP", false)));

        List<ScenarioFabricService.BlueprintView> first = asArchitect(() ->
                service.loadValidationExamples(domain.summary().id()));
        List<ScenarioFabricService.BlueprintView> second = asArchitect(() ->
                service.loadValidationExamples(domain.summary().id()));

        assertEquals(3, first.size());
        assertEquals(3, second.size());
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM scenario_blueprints WHERE domain_id=?",
                Integer.class, domain.summary().id()));

        asArchitect(() -> service.bindAsset(domain.summary().id(),
                new ScenarioFabricService.AssetRequest("SELF_SERVICE_PRODUCT", "product-1",
                        1, "EXECUTION", true, json.createObjectNode())));
        ScenarioFabricService.BlueprintView decline = first.stream()
                .filter(item -> item.name().equals("Credit Limit Decline Boundary"))
                .findFirst().orElseThrow();
        ScenarioFabricService.MissionView mission = asArchitect(() -> service.createMission(
                new ScenarioFabricService.MissionRequest(decline.id(),
                        "Validate credit decline edge",
                        "Prove purchases crossing available credit are declined without a ledger hold",
                        "QA", "AUTO", 100L,
                        Map.of("channel", "POS", "cardNetwork", "VISA", "merchantRisk", "LOW"),
                        false, null)));

        assertEquals("PLANNED", mission.status());
        assertTrue(Boolean.TRUE.equals(mission.plan().get("executable")));
        Set<String> kinds = mission.cases().stream()
                .map(ScenarioFabricService.MissionCase::caseKind)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(kinds.containsAll(Set.of("BASELINE", "BOUNDARY", "NEGATIVE", "PAIRWISE")));
    }

    private <T> T asArchitect(java.util.function.Supplier<T> work) {
        return AccessContext.callAs(architect, "test-session", work);
    }
}
