package io.forgetdm.dataset;

import io.forgetdm.audit.AuditService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.discovery.ClassificationRepository;
import io.forgetdm.policy.MaskingRuleRepository;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessControlService;
import io.forgetdm.security.AccessPrincipal;
import io.forgetdm.security.GovernedReferenceGuard;
import io.forgetdm.security.OwnershipGuard;
import io.forgetdm.subset.SubsetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataSetAuditCoverageTest {

    private static final long DATASET_ID = 91L;

    private final DataSetDefinitionRepository definitions = mock(DataSetDefinitionRepository.class);
    private final TableProfileRepository profiles = mock(TableProfileRepository.class);
    private final ColumnOverrideRepository overrides = mock(ColumnOverrideRepository.class);
    private final UserDefinedPkRepository customPks = mock(UserDefinedPkRepository.class);
    private final UserDefinedRelationshipRepository relationships = mock(UserDefinedRelationshipRepository.class);
    private final RelationshipTraversalRuleRepository traversalRules = mock(RelationshipTraversalRuleRepository.class);
    private final DataSourceService dataSources = mock(DataSourceService.class);
    private final GovernedReferenceGuard references = mock(GovernedReferenceGuard.class);
    private final AuditService audit = mock(AuditService.class);
    private DataSetService service;

    @BeforeEach
    void setUp() {
        DataSetDefinitionEntity definition = new DataSetDefinitionEntity();
        definition.setName("alpha-scope");
        definition.setDataSourceId(1L);
        definition.setSchemaName("public");
        definition.setOwnerUserId(11L);
        definition.setOwnerGroupId(101L);
        definition.setVisibility(OwnershipGuard.GROUP);
        when(definitions.findById(DATASET_ID)).thenReturn(Optional.of(definition));

        DataSourceEntity source = new DataSourceEntity();
        source.setId(1L);
        source.setName("sourceDB");
        source.setRole("SOURCE");
        when(dataSources.getSourceCapable(1L)).thenReturn(source);
        when(dataSources.get(1L)).thenReturn(source);
        when(dataSources.tables(1L, "public")).thenReturn(List.of(Map.of("table", "accounts")));
        when(profiles.save(any(TableProfileEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(overrides.save(any(ColumnOverrideEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(overrides.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(customPks.save(any(UserDefinedPkEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(customPks.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(relationships.save(any(UserDefinedRelationshipEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(traversalRules.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        service = new DataSetService(definitions, profiles, overrides, customPks, relationships, traversalRules,
                mock(SubsetService.class), dataSources, mock(ConnectionFactory.class), audit,
                mock(ClassificationRepository.class), mock(MaskingRuleRepository.class),
                new OwnershipGuard(audit), references);
    }

    @Test
    void tableProfilesAndColumnOverridesUseStructuredAuditWithoutLeakingDesignContent() {
        asAlpha(() -> {
            TableProfileEntity profile = new TableProfileEntity();
            profile.setTableName("accounts");
            profile.setIncluded(true);
            profile.setReferentialStrategy("INDEPENDENT");
            profile.setFilterExpr("ssn = '123-45-6789'");
            service.saveProfile(DATASET_ID, profile);

            ColumnOverrideEntity override = new ColumnOverrideEntity();
            override.setTableName("accounts");
            override.setColumnName("ssn");
            override.setOverrideType("LITERAL");
            override.setLiteralValue("SECRET_LITERAL");
            override.setCondExpr("balance > 1000000");
            service.saveOverride(DATASET_ID, override);
            return null;
        });

        verify(audit).record(eq("alpha-user"), eq("DATASET_PROFILE_SAVED"), eq("DATASCOPE"),
                eq("dataset-profile"), eq(String.valueOf(DATASET_ID)), eq("alpha-scope"), eq("SUCCESS"),
                eq("Saved DataScope table profile"),
                argThat(m -> m.contains("\"table\":\"accounts\"")
                        && m.contains("\"included\":true")
                        && !m.contains("123-45-6789")));
        verify(audit).record(eq("alpha-user"), eq("DATASET_OVERRIDE_SAVED"), eq("DATASCOPE"),
                eq("dataset-override"), eq(String.valueOf(DATASET_ID)), eq("alpha-scope"), eq("SUCCESS"),
                eq("Saved DataScope column override"),
                argThat(m -> m.contains("\"column\":\"ssn\"")
                        && m.contains("\"conditionConfigured\":true")
                        && !m.contains("SECRET_LITERAL")
                        && !m.contains("balance >")));
    }

    @Test
    void customKeysRelationshipsAndTraversalRulesUseStructuredAudit() {
        UserDefinedPkEntity existingPk = customPk("accounts", "account_id,customer_id");
        when(customPks.findById(301L)).thenReturn(Optional.of(existingPk));

        UserDefinedRelationshipEntity existingRel = relationship();
        when(relationships.findById(401L)).thenReturn(Optional.of(existingRel));

        RelationshipTraversalRuleEntity rule = new RelationshipTraversalRuleEntity();
        rule.setId(501L);
        rule.setRelSource("USER");
        rule.setRelRefId(401L);
        when(traversalRules.findByDatasetId(DATASET_ID)).thenReturn(List.of(rule));

        asAlpha(() -> {
            service.saveCustomPk(DATASET_ID, customPk("accounts", "account_id,customer_id"));
            service.deleteCustomPk(301L);
            service.createUserRel(DATASET_ID, relationship());
            service.updateUserRel(401L, relationship());
            service.deleteUserRel(401L);

            RelationshipTraversalRuleEntity traversal = new RelationshipTraversalRuleEntity();
            traversal.setParentTable("customers");
            traversal.setChildTable("accounts");
            traversal.setRelSource("USER");
            traversal.setRelRefId(401L);
            traversal.setTraverseDirection("Q1_ONLY");
            service.saveTraversalRules(DATASET_ID, List.of(traversal));
            return null;
        });

        verify(audit).record(eq("alpha-user"), eq("DATASET_CUSTOM_PK_SAVED"), eq("DATASCOPE"),
                eq("dataset-custom-pk"), eq(String.valueOf(DATASET_ID)), eq("alpha-scope"), eq("SUCCESS"),
                eq("Saved DataScope tool primary key"),
                argThat(m -> m.contains("\"table\":\"accounts\"") && m.contains("\"keyColumnCount\":2")));
        verify(audit).record(eq("alpha-user"), eq("DATASET_CUSTOM_PK_DELETED"), eq("DATASCOPE"),
                eq("dataset-custom-pk"), eq(String.valueOf(DATASET_ID)), eq("alpha-scope"), eq("SUCCESS"),
                eq("Deleted DataScope tool primary key"),
                argThat(m -> m.contains("\"pkId\":301") && m.contains("\"keyColumnCount\":2")));
        verify(audit).record(eq("alpha-user"), eq("USER_REL_CREATED"), eq("DATASCOPE"),
                eq("dataset-user-relationship"), eq(String.valueOf(DATASET_ID)), eq("alpha-scope"), eq("SUCCESS"),
                eq("Created DataScope tool relationship"),
                argThat(m -> m.contains("\"parentTable\":\"customers\"") && m.contains("\"columnPairCount\":1")));
        verify(audit).record(eq("alpha-user"), eq("USER_REL_UPDATED"), eq("DATASCOPE"),
                eq("dataset-user-relationship"), eq(String.valueOf(DATASET_ID)), eq("alpha-scope"), eq("SUCCESS"),
                eq("Updated DataScope tool relationship"),
                argThat(m -> m.contains("\"childTable\":\"accounts\"") && m.contains("\"columnPairCount\":1")));
        verify(audit).record(eq("alpha-user"), eq("USER_REL_DELETED"), eq("DATASCOPE"),
                eq("dataset-user-relationship"), eq(String.valueOf(DATASET_ID)), eq("alpha-scope"), eq("SUCCESS"),
                eq("Deleted DataScope tool relationship"),
                argThat(m -> m.contains("\"relationshipId\":401") && m.contains("\"cascadedTraversalRules\":1")));
        verify(audit).record(eq("alpha-user"), eq("DATASET_TRAVERSAL_RULES_SAVED"), eq("DATASCOPE"),
                eq("dataset"), eq(String.valueOf(DATASET_ID)), eq("alpha-scope"), eq("SUCCESS"),
                eq("Saved DataScope traversal rules"),
                argThat(m -> m.contains("\"ruleCount\":1")));
    }

    private <T> T asAlpha(java.util.function.Supplier<T> work) {
        AccessPrincipal principal = new AccessPrincipal(11L, "alpha-user", "Alpha", Set.of(), Set.of(),
                List.of(new AccessControlService.GroupLite(101L, "alpha")));
        return AccessContext.callAs(principal, null, work);
    }

    private static UserDefinedPkEntity customPk(String table, String columns) {
        UserDefinedPkEntity pk = new UserDefinedPkEntity();
        pk.setDatasetId(DATASET_ID);
        pk.setTableName(table);
        pk.setColumnNames(columns);
        return pk;
    }

    private static UserDefinedRelationshipEntity relationship() {
        UserDefinedRelationshipEntity rel = new UserDefinedRelationshipEntity();
        rel.setDatasetId(DATASET_ID);
        rel.setRelName("customers_accounts");
        rel.setParentTable("customers");
        rel.setParentColumns("customer_id");
        rel.setChildTable("accounts");
        rel.setChildColumns("customer_id");
        rel.setNote("do not leak this note");
        return rel;
    }
}
