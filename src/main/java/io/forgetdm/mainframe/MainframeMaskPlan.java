package io.forgetdm.mainframe;

import java.util.List;

/** Immutable, job-time snapshot of the policy rules executable against one file. */
public record MainframeMaskPlan(Long policyId, Long assetId, List<Rule> rules) {
    public MainframeMaskPlan {
        rules = rules == null ? List.of() : List.copyOf(rules);
    }

    public record Rule(String fieldPath, Long policyRuleId, String sourceTable, String sourceColumn,
                       String function, String param1, String param2, String semanticSalt) { }
}
