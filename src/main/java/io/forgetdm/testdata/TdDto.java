package io.forgetdm.testdata;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Plain-language DTOs shared across the self-service flow (plan → confirm → receipt). */
public final class TdDto {
    private TdDto() {}

    /** One business asset in an interpreted plan. */
    public record PlanAsset(String recipeKey, String name, int quantity,
                            Map<String, String> attributes, String linkedTo) {}

    /** The confirmable plan shown to the tester in business language. */
    public record Plan(String summary, String environment, int quantity,
                       List<PlanAsset> assets, Safety safety, List<String> openQuestions,
                       int estimatedSeconds) {}

    public record Safety(String dataOrigin, boolean maskingApplied, boolean approvalRequired) {}

    /** One concrete provisioned record, in the tester's vocabulary. */
    public record ProvisionedObject(String type, String id, String label,
                                    Map<String, String> attributes, String linkedTo) {}

    /** How to find the data — the answer to "where is my data". */
    public record HowToAccess(String environment, String find, String note) {}

    /** The receipt returned after provisioning. */
    public record Receipt(Long requestId, String status, String summary, String environment,
                          List<ProvisionedObject> provisioned, HowToAccess howToAccess,
                          Map<String, Object> governance, String auditRef, String createdBy) {

        public Map<String, Object> asMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("requestId", requestId);
            m.put("status", status);
            m.put("summary", summary);
            m.put("environment", environment);
            m.put("provisioned", provisioned);
            m.put("howToAccess", howToAccess);
            m.put("governance", governance);
            m.put("auditRef", auditRef);
            m.put("createdBy", createdBy);
            return m;
        }
    }
}
