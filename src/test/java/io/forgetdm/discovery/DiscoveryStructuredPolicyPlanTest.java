package io.forgetdm.discovery;

import io.forgetdm.core.mask.StructuredMaskingCodec;
import io.forgetdm.core.util.PiiPatterns;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DiscoveryStructuredPolicyPlanTest {

    @Test
    void compilesAllApprovedLogicalPathsIntoOnePhysicalColumnPlan() {
        ClassificationEntity classification = new ClassificationEntity();
        classification.setContentFormat("TEMENOS");
        classification.setLogicalPaths("$/FM[2]/NAME [FULL_NAME]; $/FM[3]/VM[1]/ID.NO [TAX_ID]");

        String encoded = DiscoveryService.structuredMaskingPlan(classification, PiiPatterns.SUGGESTED);
        StructuredMaskingCodec.Config config = StructuredMaskingCodec.decode(encoded);

        assertNotNull(encoded);
        assertEquals("TEMENOS", config.format());
        assertEquals(2, config.rules().size());
        assertEquals("FULL_NAME", config.rules().get(0).function());
        assertEquals("pii.full_name", config.rules().get(0).salt());
        assertEquals("NATIONAL_ID", config.rules().get(1).function());
    }

    @Test
    void compilesOnlyLogicalFieldsApprovedByTheAnalyst() {
        ClassificationEntity classification = new ClassificationEntity();
        classification.setContentFormat("XML");
        classification.setLogicalPaths("$/Customer/FirstName[1] [FIRST_NAME]; $/Customer/Email[1] [EMAIL]");
        classification.setStructuredReview(StructuredReviewCodec.encode(java.util.List.of(
                new StructuredReviewCodec.Field("$/Customer/FirstName[1]", "FIRST_NAME", 0.99,
                        "Yeshpal", "APPROVED", "FIRST_NAME", "PROPER", null),
                new StructuredReviewCodec.Field("$/Customer/Email[1]", "EMAIL", 0.99,
                        "yeshpal@example.test", "REJECTED", "EMAIL", "NAME_SAFE", "SAFE_DOMAIN")
        )));

        String encoded = DiscoveryService.structuredMaskingPlan(classification, PiiPatterns.SUGGESTED);
        StructuredMaskingCodec.Config config = StructuredMaskingCodec.decode(encoded);

        assertEquals(1, config.rules().size());
        assertEquals("$/Customer/FirstName[*]", config.rules().get(0).selector());
        assertEquals("FIRST_NAME", config.rules().get(0).function());
    }
}
