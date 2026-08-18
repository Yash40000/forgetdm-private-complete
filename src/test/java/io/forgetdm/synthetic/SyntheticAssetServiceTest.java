package io.forgetdm.synthetic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.provision.SyntheticGenService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SyntheticAssetServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private JdbcTemplate jdbc;
    private SyntheticAssetService service;
    private SyntheticGenService engine;
    private AccessPrincipal architect;

    @BeforeEach
    void setup() {
        DriverManagerDataSource source = new DriverManagerDataSource(
                "jdbc:h2:mem:synthetic_assets_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE forge_users(id BIGINT PRIMARY KEY)");
        jdbc.execute("CREATE TABLE forge_groups(id BIGINT PRIMARY KEY)");
        jdbc.update("INSERT INTO forge_users(id) VALUES (1)");
        jdbc.update("INSERT INTO forge_groups(id) VALUES (7)");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V80__synthetic_asset_registry.sql")).execute(source);

        AuditService audit = mock(AuditService.class);
        engine = mock(SyntheticGenService.class);
        when(engine.startGenerate(any())).thenReturn(Map.of("id", "job-101", "status", "PENDING"));
        service = new SyntheticAssetService(jdbc, json, new OwnershipGuard(audit), audit, engine);
        architect = new AccessPrincipal(1L, "architect", "Architect",
                Set.of("TDM_ARCHITECT"), Set.of("synthetic.read", "synthetic.manage", "synthetic.run"),
                List.of(new AccessControlService.GroupLite(7L, "Architecture")));
    }

    @Test
    void publishesPinnedScenarioCompilesRealPlanAndLaunchesExistingEngine() {
        SyntheticAssetService.AssetDetail modelDraft = asArchitect(() -> service.create(request(
                SyntheticAssetService.DATA_MODEL, "Retail Customer Model", """
                        {"tables":[{"name":"customers","rowCount":250,"columns":[
                          {"name":"customer_id","sqlType":"BIGINT","primaryKey":true,"generator":"SEQUENCE"},
                          {"name":"first_name","sqlType":"VARCHAR(80)","generator":"ALPHANUMERIC"}
                        ]}]}
                        """)));
        String modelId = modelDraft.asset().id();
        SyntheticAssetService.AssetDetail model = asArchitect(() -> service.publish(modelId));

        SyntheticAssetService.AssetDetail ruleDraft = asArchitect(() -> service.create(request(
                SyntheticAssetService.GENERATION_RULE, "United States First Names", """
                        {"generator":"FIRST_NAME","param1":"US","param2":"ANY","outputType":"VARCHAR"}
                        """)));
        String ruleId = ruleDraft.asset().id();
        SyntheticAssetService.AssetDetail rule = asArchitect(() -> service.publish(ruleId));

        SyntheticAssetService.AssetDetail deliveryDraft = asArchitect(() -> service.create(request(
                SyntheticAssetService.DELIVERY_PROFILE, "Portable CSV Delivery", """
                        {"receiver":"CSV","loadAction":"INSERT","targetPrep":"NONE"}
                        """)));
        String deliveryId = deliveryDraft.asset().id();
        SyntheticAssetService.AssetDetail delivery = asArchitect(() -> service.publish(deliveryId));

        String scenarioJson = """
                {"dataset":"Retail customer acceptance data","seed":84,
                 "modelRef":{"assetId":"%s"},
                 "bindings":[{"table":"customers","field":"first_name","ruleRef":{"assetId":"%s"}}],
                 "deliveryRef":{"assetId":"%s"},
                 "execution":{"mode":"SINGLE"}}
                """.formatted(model.asset().id(), rule.asset().id(), delivery.asset().id());
        SyntheticAssetService.AssetDetail scenarioDraft = asArchitect(() -> service.create(request(
                SyntheticAssetService.GENERATION_SCENARIO, "Retail Customer Acceptance Scenario", scenarioJson)));
        String scenarioId = scenarioDraft.asset().id();
        SyntheticAssetService.AssetDetail scenario = asArchitect(() -> service.publish(scenarioId));

        assertEquals(1, scenario.asset().currentVersion());
        assertEquals(3, scenario.dependencies().size());
        assertTrue(scenario.dependencies().stream().allMatch(item -> item.version() == 1));

        SyntheticAssetService.CompiledScenario compiled = asArchitect(() ->
                service.compile(scenarioId, 1));
        assertEquals("CSV", compiled.plan().receiver());
        assertEquals(84L, compiled.plan().seed());
        assertEquals(250L, compiled.plan().tables().get(0).rowCount());
        assertEquals("FIRST_NAME", compiled.plan().tables().get(0).columns().get(1).generator());
        assertEquals("US", compiled.plan().tables().get(0).columns().get(1).param1());
        assertEquals(3, compiled.components().size());

        Map<String, Object> launched = asArchitect(() ->
                service.launch(scenarioId, new SyntheticAssetService.LaunchRequest(1, 99L)));
        assertEquals("job-101", launched.get("id"));
        verify(engine).startGenerate(any(SyntheticGenService.GenPlan.class));
    }

    @Test
    void publishedVersionRemainsImmutableWhileDraftChanges() {
        SyntheticAssetService.AssetDetail ruleDraft = asArchitect(() -> service.create(request(
                SyntheticAssetService.GENERATION_RULE, "Reusable Account Status Rule", """
                        {"generator":"ENUM","param1":"ACTIVE|DORMANT","outputType":"VARCHAR"}
                        """)));
        String ruleId = ruleDraft.asset().id();
        SyntheticAssetService.AssetDetail rule = asArchitect(() -> service.publish(ruleId));
        String v1Hash = rule.versions().get(0).contentHash();

        SyntheticAssetService.AssetDetail updated = asArchitect(() -> service.update(ruleId,
                new SyntheticAssetService.AssetRequest(rule.asset().assetType(), rule.asset().name(),
                        rule.asset().description(), rule.asset().visibility(),
                        tree("{\"generator\":\"ENUM\",\"param1\":\"ACTIVE|DORMANT|CLOSED\",\"outputType\":\"VARCHAR\"}"))));

        assertEquals(1, updated.asset().currentVersion());
        assertEquals("DRAFT", updated.asset().status());
        assertEquals(v1Hash, updated.versions().get(0).contentHash());

        SyntheticAssetService.AssetDetail v2 = asArchitect(() -> service.publish(ruleId));
        assertEquals(2, v2.asset().currentVersion());
        assertEquals(2, v2.versions().size());
        assertNotEquals(v1Hash, v2.versions().get(0).contentHash());
        Map<String, Object> comparison = asArchitect(() -> service.compare(ruleId, 1, 2));
        assertTrue(((List<?>) comparison.get("added")).stream()
                .anyMatch(value -> String.valueOf(value).contains("CLOSED")));
    }

    @Test
    void rejectsDuplicateNamesBrokenRelationshipsAndUnpublishedScenarioReferences() {
        asArchitect(() -> service.create(request(
                SyntheticAssetService.GENERATION_RULE, "Stable Customer Identifier", """
                        {"generator":"SEQUENCE","outputType":"VARCHAR"}
                        """)));
        assertThrows(ApiException.class, () -> asArchitect(() -> service.create(request(
                SyntheticAssetService.GENERATION_RULE, "Stable Customer Identifier", """
                        {"generator":"UUID","outputType":"VARCHAR"}
                        """))));

        assertThrows(ApiException.class, () -> asArchitect(() -> service.create(request(
                SyntheticAssetService.DATA_MODEL, "Broken Relationship Model", """
                        {"tables":[{"name":"orders","columns":[
                          {"name":"customer_id","references":{"table":"customers","column":"customer_id"}}
                        ]}]}
                        """))));

        SyntheticAssetService.AssetDetail draftModel = asArchitect(() -> service.create(request(
                SyntheticAssetService.DATA_MODEL, "Unpublished Customer Model", """
                        {"tables":[{"name":"customers","columns":[{"name":"id","generator":"SEQUENCE"}]}]}
                        """)));
        SyntheticAssetService.AssetDetail deliveryDraft = asArchitect(() -> service.create(request(
                SyntheticAssetService.DELIVERY_PROFILE, "Published JSON Delivery", """
                        {"receiver":"JSON"}
                        """)));
        String deliveryId = deliveryDraft.asset().id();
        SyntheticAssetService.AssetDetail delivery = asArchitect(() -> service.publish(deliveryId));
        String scenario = """
                {"modelRef":{"assetId":"%s"},"deliveryRef":{"assetId":"%s"}}
                """.formatted(draftModel.asset().id(), delivery.asset().id());
        SyntheticAssetService.AssetDetail asset = asArchitect(() -> service.create(request(
                SyntheticAssetService.GENERATION_SCENARIO, "Scenario With Draft Dependency", scenario)));
        assertThrows(ApiException.class, () -> asArchitect(() -> service.publish(asset.asset().id())));
    }

    private SyntheticAssetService.AssetRequest request(String type, String name, String content) {
        return new SyntheticAssetService.AssetRequest(type, name, "Acceptance-test asset",
                "GROUP", tree(content));
    }

    private JsonNode tree(String content) {
        try {
            return json.readTree(content);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private <T> T asArchitect(java.util.function.Supplier<T> work) {
        return AccessContext.callAs(architect, "test-session", work);
    }
}
