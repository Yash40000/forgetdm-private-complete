package io.forgetdm.mainframe;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.policy.MaskingPolicyEntity;
import io.forgetdm.policy.MaskingPolicyRepository;
import io.forgetdm.policy.MaskingRuleEntity;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DataScopeMainframeFieldMappingServiceTest {

    @Test
    void compileFreezesCanonicalSemanticSaltAndRelationalLineage() {
        Fixture fixture = fixture(rule(11L, "EMAIL", true, null));
        DataScopeMainframeFieldMappingEntity mapping = mapping("CUSTOMER-REC.EMAIL", 11L);
        when(fixture.mappings.findByAssetIdAndPolicyIdOrderByOrdinalNoAscIdAsc(20L, 30L))
                .thenReturn(List.of(mapping));

        MainframeMaskPlan plan = fixture.service.compile(10L, 20L, 30L);

        assertEquals(30L, plan.policyId());
        assertEquals(20L, plan.assetId());
        assertEquals(1, plan.rules().size());
        MainframeMaskPlan.Rule frozen = plan.rules().get(0);
        assertEquals("CUSTOMER-REC.EMAIL", frozen.fieldPath());
        assertEquals("customer", frozen.sourceTable());
        assertEquals("email", frozen.sourceColumn());
        assertEquals("email", frozen.semanticSalt());
    }

    @Test
    void compileRejectsNonDeterministicAndScriptRules() {
        Fixture nonDeterministic = fixture(rule(11L, "EMAIL", false, null));
        when(nonDeterministic.mappings.findByAssetIdAndPolicyIdOrderByOrdinalNoAscIdAsc(20L, 30L))
                .thenReturn(List.of(mapping("CUSTOMER-REC.EMAIL", 11L)));
        assertTrue(assertThrows(ApiException.class,
                () -> nonDeterministic.service.compile(10L, 20L, 30L)).getMessage().contains("not safe"));

        Fixture script = fixture(rule(11L, "SCRIPT", true, "customer.email"));
        when(script.mappings.findByAssetIdAndPolicyIdOrderByOrdinalNoAscIdAsc(20L, 30L))
                .thenReturn(List.of(mapping("CUSTOMER-REC.EMAIL", 11L)));
        assertTrue(assertThrows(ApiException.class,
                () -> script.service.compile(10L, 20L, 30L)).getMessage().contains("not safe"));
    }

    @Test
    void replaceRejectsUnknownAndDuplicateCopybookPathsBeforeDeletingCurrentMap() {
        Fixture fixture = fixture(rule(11L, "EMAIL", true, null));
        DataScopeMainframeFieldMappingEntity unknown = mapping("CUSTOMER-REC.NOT-A-FIELD", 11L);
        assertTrue(assertThrows(ApiException.class,
                () -> fixture.service.replace(10L, 20L, 30L, List.of(unknown)))
                .getMessage().contains("Unknown copybook field"));
        verify(fixture.mappings, never()).deleteByAssetIdAndPolicyId(anyLong(), anyLong());

        DataScopeMainframeFieldMappingEntity first = mapping("CUSTOMER-REC.EMAIL", 11L);
        DataScopeMainframeFieldMappingEntity duplicate = mapping("customer-rec.email", 11L);
        assertTrue(assertThrows(ApiException.class,
                () -> fixture.service.replace(10L, 20L, 30L, List.of(first, duplicate)))
                .getMessage().contains("mapped more than once"));
        verify(fixture.mappings, never()).deleteByAssetIdAndPolicyId(anyLong(), anyLong());
    }

    private static Fixture fixture(MaskingRuleEntity rule) {
        DataScopeMainframeFieldMappingRepository mappings = mock(DataScopeMainframeFieldMappingRepository.class);
        DataScopeMainframeAssetService assets = mock(DataScopeMainframeAssetService.class);
        DataScopeMainframeAssetEntity asset = new DataScopeMainframeAssetEntity();
        asset.setId(20L);
        asset.setDatasetId(10L);
        asset.setCopybookId(40L);
        asset.setLogicalRole("customer-master");
        when(assets.get(10L, 20L)).thenReturn(asset);

        CopybookDefEntity copybook = new CopybookDefEntity();
        copybook.setId(40L);
        copybook.setSource("01 CUSTOMER-REC.\n   05 CUSTOMER-ID PIC X(10).\n   05 EMAIL PIC X(40).\n");
        CopybookDefRepository copybooks = mock(CopybookDefRepository.class);
        when(copybooks.findById(40L)).thenReturn(Optional.of(copybook));

        MaskingPolicyEntity policy = new MaskingPolicyEntity();
        policy.setId(30L);
        policy.setName("customer-policy");
        MaskingPolicyRepository policies = mock(MaskingPolicyRepository.class);
        when(policies.findById(30L)).thenReturn(Optional.of(policy));

        MaskingRuleRepository rules = mock(MaskingRuleRepository.class);
        when(rules.findById(11L)).thenReturn(Optional.of(rule));
        DataScopeMainframeFieldMappingService service = new DataScopeMainframeFieldMappingService(
                mappings, assets, copybooks, policies, rules, mock(OwnershipGuard.class), mock(AuditService.class));
        return new Fixture(service, mappings);
    }

    private static MaskingRuleEntity rule(long id, String function, boolean deterministic, String semanticSalt) {
        MaskingRuleEntity rule = mock(MaskingRuleEntity.class);
        when(rule.getId()).thenReturn(id);
        when(rule.getPolicyId()).thenReturn(30L);
        when(rule.getTableName()).thenReturn("customer");
        when(rule.getColumnName()).thenReturn("email");
        when(rule.getFunction()).thenReturn(function);
        when(rule.isDeterministic()).thenReturn(deterministic);
        when(rule.getSemanticSalt()).thenReturn(semanticSalt);
        return rule;
    }

    private static DataScopeMainframeFieldMappingEntity mapping(String fieldPath, long ruleId) {
        DataScopeMainframeFieldMappingEntity mapping = new DataScopeMainframeFieldMappingEntity();
        mapping.setAssetId(20L);
        mapping.setPolicyId(30L);
        mapping.setPolicyRuleId(ruleId);
        mapping.setFieldPath(fieldPath);
        return mapping;
    }

    private record Fixture(DataScopeMainframeFieldMappingService service,
                           DataScopeMainframeFieldMappingRepository mappings) { }
}
