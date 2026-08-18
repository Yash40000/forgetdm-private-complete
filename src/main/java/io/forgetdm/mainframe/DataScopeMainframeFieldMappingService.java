package io.forgetdm.mainframe;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.core.mask.MaskFunction;
import io.forgetdm.core.mask.MaskingSemantics;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.OwnershipGuard;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Governed copybook-to-policy bindings and immutable job-plan compilation. */
@Service
public class DataScopeMainframeFieldMappingService {
    private static final Set<MaskFunction> SALT_INDEPENDENT = Set.of(
            MaskFunction.PASSTHROUGH, MaskFunction.NULLIFY, MaskFunction.FIXED, MaskFunction.AGE);
    private static final Set<MaskFunction> CROSS_DOMAIN_UNSAFE = Set.of(
            MaskFunction.SEQUENCE, MaskFunction.SCRIPT);

    private final DataScopeMainframeFieldMappingRepository mappings;
    private final DataScopeMainframeAssetService assets;
    private final CopybookDefRepository copybooks;
    private final MaskingPolicyRepository policies;
    private final MaskingRuleRepository rules;
    private final OwnershipGuard ownership;
    private final AuditService audit;

    public DataScopeMainframeFieldMappingService(DataScopeMainframeFieldMappingRepository mappings,
                                                  DataScopeMainframeAssetService assets,
                                                  CopybookDefRepository copybooks,
                                                  MaskingPolicyRepository policies,
                                                  MaskingRuleRepository rules,
                                                  OwnershipGuard ownership,
                                                  AuditService audit) {
        this.mappings = mappings;
        this.assets = assets;
        this.copybooks = copybooks;
        this.policies = policies;
        this.rules = rules;
        this.ownership = ownership;
        this.audit = audit;
    }

    public List<DataScopeMainframeFieldMappingEntity> list(Long datasetId, Long assetId, Long policyId) {
        assets.get(datasetId, assetId);
        visiblePolicy(policyId);
        return mappings.findByAssetIdAndPolicyIdOrderByOrdinalNoAscIdAsc(assetId, policyId);
    }

    @Transactional
    public List<DataScopeMainframeFieldMappingEntity> replace(Long datasetId, Long assetId, Long policyId,
                                                               List<DataScopeMainframeFieldMappingEntity> requested) {
        DataScopeMainframeAssetEntity asset = assets.get(datasetId, assetId);
        visiblePolicy(policyId);
        Map<String, String> knownPaths = knownPaths(asset.getCopybookId());
        List<Validated> validated = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        int ordinal = 0;
        for (DataScopeMainframeFieldMappingEntity incoming : requested == null
                ? List.<DataScopeMainframeFieldMappingEntity>of() : requested) {
            if (incoming == null || incoming.getPolicyRuleId() == null) {
                throw ApiException.bad("Every file field mapping requires policyRuleId");
            }
            String normalizedPath = normalizePath(incoming.getFieldPath());
            String canonicalPath = knownPaths.get(normalizedPath);
            if (canonicalPath == null) {
                throw ApiException.bad("Unknown copybook field path '" + incoming.getFieldPath() + "'");
            }
            if (!seen.add(normalizedPath)) {
                throw ApiException.bad("Copybook field '" + canonicalPath + "' is mapped more than once");
            }
            MaskingRuleEntity rule = rule(incoming.getPolicyRuleId(), policyId);
            validateCrossDomainRule(rule);
            validated.add(new Validated(canonicalPath, rule, ordinal++));
        }

        mappings.deleteByAssetIdAndPolicyId(assetId, policyId);
        mappings.flush();
        List<DataScopeMainframeFieldMappingEntity> saved = new ArrayList<>();
        for (Validated item : validated) {
            DataScopeMainframeFieldMappingEntity entity = new DataScopeMainframeFieldMappingEntity();
            entity.setAssetId(assetId);
            entity.setPolicyId(policyId);
            entity.setPolicyRuleId(item.rule().getId());
            entity.setFieldPath(item.fieldPath());
            entity.setOrdinalNo(item.ordinal());
            entity.setCreatedAt(Instant.now());
            entity.setUpdatedAt(Instant.now());
            saved.add(mappings.save(entity));
        }
        audit.record(actor(), "DATASCOPE_MAINFRAME_FIELD_MAPPINGS_REPLACED", "MASKING",
                "datascope-mainframe-asset", String.valueOf(assetId), asset.getLogicalRole(), "SUCCESS",
                "Bound copybook fields to governed policy rules",
                "{\"datasetId\":" + datasetId + ",\"policyId\":" + policyId
                        + ",\"mappingCount\":" + saved.size() + "}");
        return saved;
    }

    /** Compile and freeze all executable values so a later policy edit cannot alter an active job. */
    public MainframeMaskPlan compile(Long datasetId, Long assetId, Long policyId) {
        DataScopeMainframeAssetEntity asset = assets.get(datasetId, assetId);
        visiblePolicy(policyId);
        List<DataScopeMainframeFieldMappingEntity> configured =
                mappings.findByAssetIdAndPolicyIdOrderByOrdinalNoAscIdAsc(assetId, policyId);
        if (configured.isEmpty()) {
            throw ApiException.bad("Mainframe asset '" + asset.getLogicalRole()
                    + "' has no copybook field mappings for policy " + policyId);
        }
        List<MainframeMaskPlan.Rule> plan = new ArrayList<>();
        for (DataScopeMainframeFieldMappingEntity mapping : configured) {
            MaskingRuleEntity rule = rule(mapping.getPolicyRuleId(), policyId);
            validateCrossDomainRule(rule);
            String salt = MaskingSemantics.canonicalSalt(rule.getFunction(), rule.getSemanticSalt());
            if (salt == null) {
                // SALT_INDEPENDENT functions do not consume this value, but freezing a stable identifier
                // keeps manifests uniform and makes parity evidence easy to inspect.
                salt = rule.getTableName().toLowerCase(Locale.ROOT) + "."
                        + rule.getColumnName().toLowerCase(Locale.ROOT);
            }
            plan.add(new MainframeMaskPlan.Rule(mapping.getFieldPath(), rule.getId(),
                    rule.getTableName(), rule.getColumnName(), rule.getFunction(),
                    rule.getParam1(), rule.getParam2(), salt));
        }
        plan.sort(Comparator.comparingInt((MainframeMaskPlan.Rule item) ->
                        MaskingSemantics.evaluationPriority(item.function()))
                .thenComparing(MainframeMaskPlan.Rule::fieldPath, String.CASE_INSENSITIVE_ORDER));
        return new MainframeMaskPlan(policyId, assetId, plan);
    }

    public List<Snapshot> snapshot(Long datasetId) {
        List<Snapshot> out = new ArrayList<>();
        for (DataScopeMainframeAssetEntity asset : assets.list(datasetId)) {
            for (DataScopeMainframeFieldMappingEntity mapping :
                    mappings.findByAssetIdOrderByPolicyIdAscOrdinalNoAscIdAsc(asset.getId())) {
                out.add(new Snapshot(asset.getLogicalRole(), mapping.getPolicyId(), mapping.getPolicyRuleId(),
                        mapping.getFieldPath(), mapping.getOrdinalNo()));
            }
        }
        return out;
    }

    @Transactional
    public void restore(Long datasetId, List<Snapshot> snapshot) {
        Map<String, DataScopeMainframeAssetEntity> assetByRole = assets.list(datasetId).stream()
                .collect(Collectors.toMap(asset -> asset.getLogicalRole().toLowerCase(Locale.ROOT), asset -> asset));
        Map<String, List<Snapshot>> groups = new LinkedHashMap<>();
        for (Snapshot item : snapshot == null ? List.<Snapshot>of() : snapshot) {
            String key = item.logicalRole().toLowerCase(Locale.ROOT) + "\u0000" + item.policyId();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(item);
        }
        for (List<Snapshot> group : groups.values()) {
            Snapshot first = group.get(0);
            DataScopeMainframeAssetEntity asset = assetByRole.get(first.logicalRole().toLowerCase(Locale.ROOT));
            if (asset == null) throw ApiException.bad("Cannot restore mappings for missing mainframe role '"
                    + first.logicalRole() + "'");
            List<DataScopeMainframeFieldMappingEntity> requested = group.stream()
                    .sorted(Comparator.comparingInt(Snapshot::ordinalNo))
                    .map(item -> {
                        DataScopeMainframeFieldMappingEntity entity = new DataScopeMainframeFieldMappingEntity();
                        entity.setFieldPath(item.fieldPath());
                        entity.setPolicyRuleId(item.policyRuleId());
                        entity.setOrdinalNo(item.ordinalNo());
                        return entity;
                    }).toList();
            replace(datasetId, asset.getId(), first.policyId(), requested);
        }
    }

    private void validateCrossDomainRule(MaskingRuleEntity rule) {
        MaskFunction function;
        try { function = MaskFunction.valueOf(rule.getFunction().trim().toUpperCase(Locale.ROOT)); }
        catch (Exception e) { throw ApiException.bad("Policy rule " + rule.getId() + " has an invalid function"); }
        if (!rule.isDeterministic() || CROSS_DOMAIN_UNSAFE.contains(function)) {
            throw ApiException.bad("Policy rule " + rule.getId() + " (" + function
                    + ") is not safe for deterministic table/file parity");
        }
        if (!SALT_INDEPENDENT.contains(function)
                && MaskingSemantics.canonicalSalt(rule.getFunction(), rule.getSemanticSalt()) == null) {
            throw ApiException.bad("Policy rule " + rule.getId() + " (" + function
                    + ") needs a semanticSalt before it can be mapped to a copybook field");
        }
    }

    private MaskingRuleEntity rule(Long ruleId, Long policyId) {
        MaskingRuleEntity rule = rules.findById(ruleId)
                .orElseThrow(() -> ApiException.notFound("Policy rule " + ruleId + " not found"));
        if (!policyId.equals(rule.getPolicyId())) {
            throw ApiException.bad("Policy rule " + ruleId + " does not belong to policy " + policyId);
        }
        return rule;
    }

    private MaskingPolicyEntity visiblePolicy(Long policyId) {
        if (policyId == null) throw ApiException.bad("policyId is required for mainframe file masking");
        MaskingPolicyEntity policy = policies.findById(policyId)
                .orElseThrow(() -> ApiException.notFound("Policy " + policyId + " not found"));
        ownership.assertCanSee("policy", policyId, policy.getOwnerUserId(),
                policy.getOwnerGroupId(), policy.getVisibility());
        return policy;
    }

    private Map<String, String> knownPaths(Long copybookId) {
        CopybookDefEntity copybook = copybooks.findById(copybookId)
                .orElseThrow(() -> ApiException.notFound("Copybook " + copybookId + " not found"));
        var parsed = CopybookSupport.parse(copybook.getSource());
        Map<String, String> result = new LinkedHashMap<>();
        CopybookSupport.structuralFields(parsed, parsed.primaryRecord()).forEach(field -> {
            String path = CopybookSupport.stripSubscripts(field.path());
            result.putIfAbsent(normalizePath(path), path);
        });
        return result;
    }

    private static String normalizePath(String value) {
        if (value == null || value.isBlank()) throw ApiException.bad("fieldPath is required");
        return CopybookSupport.stripSubscripts(value.trim()).toUpperCase(Locale.ROOT);
    }

    private static String actor() {
        return AccessContext.current().map(principal -> principal.username()).orElse("system");
    }

    private record Validated(String fieldPath, MaskingRuleEntity rule, int ordinal) { }
    public record Snapshot(String logicalRole, Long policyId, Long policyRuleId,
                           String fieldPath, int ordinalNo) { }
}
