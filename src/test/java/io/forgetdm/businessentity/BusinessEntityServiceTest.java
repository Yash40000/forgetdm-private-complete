package io.forgetdm.businessentity;

import io.forgetdm.audit.AuditService;
import io.forgetdm.dataset.DataSetDefinitionEntity;
import io.forgetdm.dataset.DataSetDefinitionRepository;
import io.forgetdm.dataset.DataSetService;
import io.forgetdm.dataset.TableProfileEntity;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceRepository;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BusinessEntityServiceTest {

    @Test
    void createsCrossApplicationArchitectureAtomically() {
        BusinessEntityDefinitionRepository defs = mock(BusinessEntityDefinitionRepository.class);
        BusinessEntityMemberRepository members = mock(BusinessEntityMemberRepository.class);
        DataSetDefinitionRepository datasets = mock(DataSetDefinitionRepository.class);
        DataSetService dataSetService = mock(DataSetService.class);
        DataSourceRepository dataSources = mock(DataSourceRepository.class);
        AuditService audit = mock(AuditService.class);
        AtomicReference<BusinessEntityDefinitionEntity> savedDef = new AtomicReference<>();
        AtomicReference<List<BusinessEntityMemberEntity>> savedMembers = new AtomicReference<>(List.of());

        DataSourceEntity core = source(10L, "Core Banking");
        DataSourceEntity cards = source(20L, "Card Processing");
        when(dataSources.findById(10L)).thenReturn(Optional.of(core));
        when(dataSources.findById(20L)).thenReturn(Optional.of(cards));
        when(dataSources.findAllById(any())).thenReturn(List.of(core, cards));
        when(defs.findByName(any())).thenReturn(Optional.empty());
        when(defs.findById(1L)).thenAnswer(invocation -> Optional.ofNullable(savedDef.get()));
        when(defs.save(any())).thenAnswer(invocation -> {
            BusinessEntityDefinitionEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) ReflectionTestUtils.setField(entity, "id", 1L);
            savedDef.set(entity);
            return entity;
        });
        when(members.findByEntityIdOrderByOrdinalNoAscIdAsc(1L)).thenAnswer(invocation -> savedMembers.get());
        when(members.saveAll(any())).thenAnswer(invocation -> {
            List<BusinessEntityMemberEntity> rows = new ArrayList<>(invocation.getArgument(0));
            savedMembers.set(rows);
            return rows;
        });

        BusinessEntityMemberEntity customer = architectureMember("core_customers", 10L, "CORE", "CUSTOMERS", "CUSTOMER_ID");
        BusinessEntityMemberEntity cardholder = architectureMember("cards_cardholders", 20L, "CARDS", "CARDHOLDERS", "CIF_ID");
        cardholder.setJoinToRole("core_customers");
        cardholder.setRelationshipJson("{\"source\":\"ENTITY_CROSSWALK\"}");
        cardholder.setFieldRulesJson("[{\"column\":\"CIF_ID\",\"maskFunction\":\"FORMAT_PRESERVE\"}]");

        BusinessEntityService service = new BusinessEntityService(defs, members, datasets, dataSetService, dataSources,
                audit, new OwnershipGuard(audit));
        BusinessEntityService.BusinessEntityDetail detail = service.createArchitecture(
                new BusinessEntityService.ArchitectureCreateRequest("Customer ecosystem", "Cross-app customer", "Banking",
                        "CUSTOMERS", "CUSTOMER_ID", List.of(customer, cardholder)));

        assertEquals("Customer ecosystem", detail.entity().getName());
        assertEquals("CUSTOMERS", detail.entity().getRootTable());
        assertEquals(2, detail.members().size());
        assertEquals("core_customers", detail.members().get(1).getJoinToRole());
        assertEquals(cardholder.getFieldRulesJson(), detail.members().get(1).getFieldRulesJson());
        verify(audit).log(any(), eq("BUSINESS_ENTITY_ARCHITECTURE_CREATE"), contains("2 data sources"));
    }

    @Test
    void rejectsArchitectureThatDoesNotCrossDataSources() {
        BusinessEntityDefinitionRepository defs = mock(BusinessEntityDefinitionRepository.class);
        BusinessEntityMemberRepository members = mock(BusinessEntityMemberRepository.class);
        DataSetDefinitionRepository datasets = mock(DataSetDefinitionRepository.class);
        DataSetService dataSetService = mock(DataSetService.class);
        DataSourceRepository dataSources = mock(DataSourceRepository.class);
        AuditService audit = mock(AuditService.class);
        BusinessEntityService service = new BusinessEntityService(defs, members, datasets, dataSetService, dataSources,
                audit, new OwnershipGuard(audit));
        BusinessEntityMemberEntity customer = architectureMember("core_customers", 10L, "CORE", "CUSTOMERS", "CUSTOMER_ID");

        assertThrows(RuntimeException.class, () -> service.createArchitecture(
                new BusinessEntityService.ArchitectureCreateRequest("Customer ecosystem", null, "Banking",
                        "CUSTOMERS", "CUSTOMER_ID", List.of(customer))));
        verify(defs, never()).save(any());
        verify(members, never()).saveAll(any());
    }

    @Test
    void createsBusinessEntityFromDataScopeProfiles() {
        BusinessEntityDefinitionRepository defs = mock(BusinessEntityDefinitionRepository.class);
        BusinessEntityMemberRepository members = mock(BusinessEntityMemberRepository.class);
        DataSetDefinitionRepository datasets = mock(DataSetDefinitionRepository.class);
        DataSetService dataSetService = mock(DataSetService.class);
        DataSourceRepository dataSources = mock(DataSourceRepository.class);
        AuditService audit = mock(AuditService.class);
        AtomicReference<BusinessEntityDefinitionEntity> savedDef = new AtomicReference<>();
        AtomicReference<List<BusinessEntityMemberEntity>> savedMembers = new AtomicReference<>(List.of());
        AtomicLong memberIds = new AtomicLong(100L);

        DataSetDefinitionEntity ds = new DataSetDefinitionEntity();
        ReflectionTestUtils.setField(ds, "id", 7L);
        ds.setName("retail-customer-scope");
        ds.setDataSourceId(10L);
        ds.setSchemaName("public");
        ds.setDriverTable("customers");

        TableProfileEntity customer = profile(7L, "customers", null, null, true);
        TableProfileEntity account = profile(7L, "accounts", 20L, "finance", false);

        when(dataSetService.get(7L)).thenReturn(ds);
        when(dataSetService.listProfiles(7L)).thenReturn(List.of(customer, account));
        when(dataSetService.listUserRels(7L)).thenReturn(List.of());
        when(dataSetService.customPkMap(7L)).thenReturn(Map.of("customers", "customer_id", "accounts", "account_id"));
        when(datasets.existsById(7L)).thenReturn(true);
        when(datasets.findById(7L)).thenReturn(Optional.of(ds));
        DataSourceEntity source = source(10L, "source");
        DataSourceEntity finance = source(20L, "finance");
        when(dataSources.findById(10L)).thenReturn(Optional.of(source));
        when(dataSources.findById(20L)).thenReturn(Optional.of(finance));
        when(dataSources.findAllById(any())).thenReturn(List.of(source, finance));
        when(defs.findByName(any())).thenReturn(Optional.empty());
        when(defs.findById(1L)).thenAnswer(inv -> Optional.ofNullable(savedDef.get()));
        when(defs.save(any())).thenAnswer(inv -> {
            BusinessEntityDefinitionEntity e = inv.getArgument(0);
            if (e.getId() == null) ReflectionTestUtils.setField(e, "id", 1L);
            savedDef.set(e);
            return e;
        });
        when(members.saveAll(any())).thenAnswer(inv -> {
            List<BusinessEntityMemberEntity> rows = new ArrayList<>(inv.getArgument(0));
            rows.forEach(row -> {
                if (row.getId() == null) ReflectionTestUtils.setField(row, "id", memberIds.getAndIncrement());
            });
            savedMembers.set(rows);
            return rows;
        });
        when(members.findByEntityIdOrderByOrdinalNoAscIdAsc(1L)).thenAnswer(inv -> savedMembers.get());

        BusinessEntityService service = new BusinessEntityService(defs, members, datasets, dataSetService, dataSources,
                audit, new OwnershipGuard(audit));
        BusinessEntityService.BusinessEntityDetail detail = service.createFromDataset(7L,
                new BusinessEntityService.FromDatasetRequest("Customer 360", "Retail customer graph", "Retail Banking"));

        assertEquals("Customer 360", detail.entity().getName());
        assertEquals("customers", detail.entity().getRootTable());
        assertEquals("customer_id", detail.entity().getBusinessKeyColumns());
        assertEquals(2, detail.members().size());
        assertEquals(10L, detail.members().get(0).getDataSourceId());
        assertEquals("public", detail.members().get(0).getSchemaName());
        assertEquals(20L, detail.members().get(1).getDataSourceId());
        assertEquals("finance", detail.members().get(1).getSchemaName());
        assertEquals(false, detail.members().get(1).isIncludeInSubset());
        assertNotNull(detail.entity().getUpdatedAt());

        List<Long> originalIds = detail.members().stream().map(BusinessEntityMemberEntity::getId).toList();
        List<BusinessEntityMemberEntity> resaved = service.replaceMembers(1L, detail.members());
        assertEquals(originalIds, resaved.stream().map(BusinessEntityMemberEntity::getId).toList());
        verify(members, never()).deleteAll(any());
        verify(audit, atLeastOnce()).log(any(), startsWith("BUSINESS_ENTITY"), any());
    }

    private static TableProfileEntity profile(Long datasetId, String table, Long sourceId, String schema, boolean included) {
        TableProfileEntity p = new TableProfileEntity();
        p.setDatasetId(datasetId);
        p.setTableName(table);
        p.setSourceDataSourceId(sourceId);
        p.setSourceSchemaName(schema);
        p.setIncluded(included);
        return p;
    }

    private static DataSourceEntity source(Long id, String name) {
        DataSourceEntity source = new DataSourceEntity();
        source.setId(id);
        source.setName(name);
        source.setVisibility(OwnershipGuard.SHARED);
        return source;
    }

    private static BusinessEntityMemberEntity architectureMember(String role, Long sourceId, String schema,
                                                                  String table, String keyColumn) {
        BusinessEntityMemberEntity member = new BusinessEntityMemberEntity();
        member.setLogicalRole(role);
        member.setSystemName(role.split("_")[0]);
        member.setDataSourceId(sourceId);
        member.setSchemaName(schema);
        member.setTableName(table);
        member.setKeyColumns(keyColumn);
        member.setIncludeInSubset(true);
        member.setIncludeInSynthetic(true);
        return member;
    }
}
