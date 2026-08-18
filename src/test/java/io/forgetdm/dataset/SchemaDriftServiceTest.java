package io.forgetdm.dataset;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.platform.ClusterLeaseService;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Types;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaDriftServiceTest {

    private SchemaDriftService service;
    private DataSetDefinitionEntity definition;

    @BeforeEach
    void setUp() {
        ColumnOverrideRepository overrides = mock(ColumnOverrideRepository.class);
        UserDefinedPkRepository customPks = mock(UserDefinedPkRepository.class);
        UserDefinedRelationshipRepository relationships = mock(UserDefinedRelationshipRepository.class);
        when(overrides.findByDatasetIdAndTableName(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(List.of());
        when(customPks.findByDatasetId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(relationships.findByDatasetId(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        service = new SchemaDriftService(
                mock(DataSetDefinitionRepository.class), mock(TableProfileRepository.class), overrides,
                customPks, relationships, mock(DataSourceService.class), mock(ConnectionFactory.class),
                mock(OwnershipGuard.class), mock(AuditService.class), mock(JdbcTemplate.class),
                new ObjectMapper().findAndRegisterModules(), mock(ClusterLeaseService.class));
        definition = new DataSetDefinitionEntity();
        definition.setName("Customer banking scope");
        definition.setDriverTable("customers");
    }

    @Test
    void detectsDestructiveColumnAndPrimaryKeyChangesAsBlocking() {
        SchemaDriftService.TableSnapshot before = table(
                List.of(column("id", Types.BIGINT, "BIGINT", 19L, false),
                        column("customer_name", Types.VARCHAR, "VARCHAR", 120L, true)),
                List.of("id"), List.of(), List.of(), List.of());
        SchemaDriftService.TableSnapshot after = table(
                List.of(column("id", Types.VARCHAR, "VARCHAR", 30L, false)),
                List.of(), List.of(), List.of(), List.of());

        List<SchemaDriftService.DriftIssue> issues = compare(before, after);

        assertThat(issues).extracting(SchemaDriftService.DriftIssue::type)
                .contains("DATA_TYPE_CHANGED", "COLUMN_MISSING", "PRIMARY_KEY_CHANGED");
        assertThat(issues).filteredOn(issue -> issue.type().equals("COLUMN_MISSING"))
                .allMatch(issue -> issue.severity().equals("BLOCKER"));
        assertThat(issues).filteredOn(issue -> issue.type().equals("DATA_TYPE_CHANGED"))
                .allMatch(issue -> issue.severity().equals("HIGH"));
    }

    @Test
    void detectsRelationshipConstraintAndUniqueIndexChanges() {
        var oldForeignKey = new SchemaDriftService.ForeignKeySnapshot(
                "fk_account_customer", List.of("customer_id"), "bank", "customers",
                List.of("id"), (short) 3, (short) 3);
        var newForeignKey = new SchemaDriftService.ForeignKeySnapshot(
                "fk_account_customer", List.of("party_id"), "bank", "customers",
                List.of("id"), (short) 3, (short) 3);
        SchemaDriftService.TableSnapshot before = table(
                List.of(column("id", Types.BIGINT, "BIGINT", 19L, false)), List.of("id"),
                List.of(oldForeignKey), List.of(new SchemaDriftService.IndexSnapshot("uk_account", true, List.of("id"))),
                List.of(new SchemaDriftService.CheckSnapshot("ck_id", "id > 0")));
        SchemaDriftService.TableSnapshot after = table(
                List.of(column("id", Types.BIGINT, "BIGINT", 19L, false)), List.of("id"),
                List.of(newForeignKey), List.of(new SchemaDriftService.IndexSnapshot("ix_account", false, List.of("id"))),
                List.of(new SchemaDriftService.CheckSnapshot("ck_id", "id >= 0")));

        assertThat(compare(before, after)).extracting(SchemaDriftService.DriftIssue::type)
                .contains("FOREIGN_KEY_CHANGED", "UNIQUE_CONSTRAINT_CHANGED", "CHECK_CONSTRAINT_CHANGED");
    }

    @Test
    void classifiesAdditiveAndWideningChangesAsNonBlockingEvidence() {
        SchemaDriftService.TableSnapshot before = table(
                List.of(column("id", Types.BIGINT, "BIGINT", 19L, false),
                        column("description", Types.VARCHAR, "VARCHAR", 40L, true)),
                List.of("id"), List.of(), List.of(), List.of());
        SchemaDriftService.TableSnapshot after = table(
                List.of(column("id", Types.BIGINT, "BIGINT", 19L, false),
                        column("description", Types.VARCHAR, "VARCHAR", 200L, true),
                        column("created_by", Types.VARCHAR, "VARCHAR", 80L, true)),
                List.of("id"), List.of(), List.of(), List.of());

        List<SchemaDriftService.DriftIssue> issues = compare(before, after);

        assertThat(issues).extracting(SchemaDriftService.DriftIssue::type)
                .containsExactlyInAnyOrder("LENGTH_WIDENED", "COLUMN_ADDED");
        assertThat(issues).allMatch(issue -> issue.severity().equals("INFO"));
    }

    private List<SchemaDriftService.DriftIssue> compare(SchemaDriftService.TableSnapshot before,
                                                         SchemaDriftService.TableSnapshot after) {
        return service.compare(12L, definition,
                new SchemaDriftService.SchemaSnapshot(Instant.parse("2026-01-01T00:00:00Z"), List.of(before)),
                new SchemaDriftService.SchemaSnapshot(Instant.parse("2026-01-02T00:00:00Z"), List.of(after)));
    }

    private SchemaDriftService.TableSnapshot table(List<SchemaDriftService.ColumnSnapshot> columns,
                                                    List<String> primaryKey,
                                                    List<SchemaDriftService.ForeignKeySnapshot> foreignKeys,
                                                    List<SchemaDriftService.IndexSnapshot> indexes,
                                                    List<SchemaDriftService.CheckSnapshot> checks) {
        return new SchemaDriftService.TableSnapshot(
                "SOURCE", 1L, "bank-source", "bank", "customers", "customers",
                true, true, "POSTGRES", "PK_UK_FK_INDEX_CHECK", null,
                columns, primaryKey, foreignKeys, indexes, checks);
    }

    private SchemaDriftService.ColumnSnapshot column(String name, int jdbcType, String type,
                                                      Long length, boolean nullable) {
        return new SchemaDriftService.ColumnSnapshot(name, 1, type, jdbcType, length, 0,
                nullable, false, null);
    }
}
