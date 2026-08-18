package io.forgetdm.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScenarioCompilerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ScenarioCompiler compiler = new ScenarioCompiler(json);

    @Test
    void compilesBusinessIntentIntoDeterministicCoveredExecutionPlan() throws Exception {
        ScenarioCompiler.Compilation result = compiler.compile(input("product-42", "SYNTHETIC"));

        assertTrue(result.executable());
        assertEquals("product-42", result.productId());
        assertEquals("SYNTHETIC", result.strategy());
        assertEquals(result.cases().size(), result.coverage().get("caseCount"));
        assertEquals(6, ((List<?>) result.plan().get("steps")).size());

        Set<String> kinds = result.cases().stream()
                .map(ScenarioCompiler.CaseSpec::kind)
                .collect(Collectors.toSet());
        assertTrue(kinds.containsAll(Set.of(
                "BASELINE", "BOUNDARY", "NEGATIVE", "STATE_TRANSITION", "PAIRWISE")));
        assertTrue(result.cases().stream()
                .filter(item -> "PAIRWISE".equals(item.kind()))
                .allMatch(item -> item.inputs().containsKey("channel")
                        && item.inputs().containsKey("customerTier")));
        assertEquals(result.cases().size(), result.cases().stream()
                .map(ScenarioCompiler.CaseSpec::key).distinct().count());
    }

    @Test
    void retainsCoverageButBlocksLaunchUntilApprovedProductIsBound() throws Exception {
        ScenarioCompiler.Compilation result = compiler.compile(input(null, null));

        assertFalse(result.executable());
        assertFalse(result.cases().isEmpty());
        assertTrue(((List<?>) result.plan().get("blockers")).stream()
                .map(String::valueOf)
                .anyMatch(message -> message.contains("approved self-service product")));
    }

    @Test
    void requestedStrategyOverridesProductDefaultWithoutChangingCaseCoverage() throws Exception {
        ScenarioCompiler.Input base = input("product-42", "DATASCOPE");
        ScenarioCompiler.Input requested = new ScenarioCompiler.Input(
                base.missionId(), base.domainName(), base.topologyVersion(), base.topologyHash(),
                base.blueprintVersion(), base.preconditions(), base.event(), base.expected(),
                base.coverage(), base.delivery(), base.questionnaire(), base.verification(),
                base.parameters(), "HYBRID", base.requestedCount(), base.environment(),
                base.boundProductId(), base.boundProductType());

        ScenarioCompiler.Compilation result = compiler.compile(requested);

        assertEquals("HYBRID", result.strategy());
        assertTrue(result.executable());
        assertEquals(result.cases().size(), result.coverage().get("caseCount"));
    }

    private ScenarioCompiler.Input input(String productId, String productType) throws Exception {
        return new ScenarioCompiler.Input(
                "mission-1", "Retail cards", 3, "abc123", 2,
                json.readTree("""
                        [{"field":"account.status","operator":"EQUALS","value":"ACTIVE","required":true},
                         {"field":"account.limit","operator":"EQUALS","value":1000,"required":true}]
                        """),
                json.readTree("""
                        {"action":"authorize payment","parameters":{"amount":100}}
                        """),
                json.readTree("""
                        [{"field":"authorization.status","operator":"EQUALS","value":"APPROVED"}]
                        """),
                json.readTree("""
                        {"techniques":["BASELINE","BOUNDARY","NEGATIVE","STATE_TRANSITION","PAIRWISE"],
                         "boundaries":[{"field":"account.limit","value":1000}],
                         "parameters":{"channel":["WEB","MOBILE","BRANCH"],
                                       "customerTier":["STANDARD","PREMIUM"]}}
                        """),
                json.readTree("""
                        {"defaultStrategy":"AUTO"}
                        """),
                json.readTree("""
                        [{"key":"channel","type":"SELECT","options":["WEB","MOBILE","BRANCH"]},
                         {"key":"customerTier","type":"SELECT","options":["STANDARD","PREMIUM"]}]
                        """),
                json.readTree("""
                        {"predicates":[{"field":"authorization.status","operator":"EQUALS","value":"APPROVED"}]}
                        """),
                Map.of("channel", "WEB", "customerTier", "PREMIUM"),
                "AUTO", 250, "QA", productId, productType);
    }
}
