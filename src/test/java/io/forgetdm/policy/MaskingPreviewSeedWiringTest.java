package io.forgetdm.policy;

import io.forgetdm.core.mask.MaskingEngine;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaskingPreviewSeedWiringTest {

    @Test
    void maskingStudioForwardsTheSeedWithoutCaseFoldingOrPreTrimming() {
        RecordingEngine engine = new RecordingEngine();
        PolicyController controller = new PolicyController(null, null, engine, null, null, null, null);

        controller.preview(Map.of(
                "function", "TOKENIZE",
                "value", "customer-10025",
                "param1", "TKN_",
                "param2", "32",
                "seed", "  Mixed Case Seed  "));
        controller.preview(Map.of(
                "function", "TOKENIZE",
                "value", "customer-10025",
                "param1", "TKN_",
                "param2", "32",
                "seed", "   "));

        assertEquals(List.of("  Mixed Case Seed  ", "   "), engine.seeds);
    }

    @Test
    void maskingStudioPreviewsStructuredXmlWithTheSameDeterministicEngine() {
        MaskingEngine engine = new MaskingEngine("mask003-preview-secret");
        PolicyController controller = new PolicyController(null, null, engine, null, null, null, null);
        String config = """
                {"format":"XML","rules":[
                  {"selector":"$/CustomerProfile[*]/FullName[*]","function":"FULL_NAME","salt":"pii.full_name","param1":"FIRST LAST"},
                  {"selector":"$/CustomerProfile[*]/CCN[*]","function":"CREDIT_CARD","salt":"pii.credit_card","param1":"VALID_PRESERVE_BIN","param2":"PRESERVE_FORMAT"}
                ]}
                """;
        String original = "<CustomerProfile><FullName>Yeshpal Singh Solanki</FullName><CCN>4111111111111111</CCN></CustomerProfile>";

        Map<String, String> first = controller.preview(Map.of(
                "structuredConfig", config, "value", original, "seed", "proof-seed"));
        Map<String, String> second = controller.preview(Map.of(
                "structuredConfig", config, "value", original, "seed", "proof-seed"));

        assertEquals(first.get("masked"), second.get("masked"));
        assertNotEquals(original, first.get("masked"));
        assertTrue(first.get("masked").startsWith("<CustomerProfile>"));
        assertTrue(first.get("masked").contains("<FullName>"));
        assertTrue(first.get("masked").contains("<CCN>411111"));
    }

    private static final class RecordingEngine extends MaskingEngine {
        private final List<String> seeds = new ArrayList<>();

        private RecordingEngine() {
            super("mask003-preview-secret");
        }

        @Override
        public MaskingEngine withSeed(String seed) {
            seeds.add(seed);
            return super.withSeed(seed);
        }
    }
}
