package io.forgetdm.datasource;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.security.OwnershipGuard;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceMetadataContractTest {

    @Test
    void browseAndTypedResolutionReturnCanonicalMetadataForTablesColumnsViewsAndFks() throws Exception {
        Fixture fixture = fixture();
        fixture.createCoreObjects();

        List<Map<String, Object>> schemas = fixture.service.schemas(1L);
        assertTrue(schemas.stream().anyMatch(row -> "APP_A".equals(row.get("schema"))));
        assertTrue(schemas.stream().anyMatch(row -> "APP_B".equals(row.get("schema"))));

        List<Map<String, Object>> appTables = fixture.service.tables(1L, "app_a");
        assertEquals(List.of("customer_view", "customers", "orders", "select"),
                appTables.stream().map(row -> String.valueOf(row.get("table"))).toList());

        List<Map<String, Object>> columns = fixture.service.columns(1L, "APP_A", "customers");
        assertEquals(List.of("customer_id", "Customer Name", "名"),
                columns.stream().map(row -> String.valueOf(row.get("column"))).toList());

        List<Map<String, Object>> fks = fixture.service.foreignKeys(1L, "APP_A", "orders");
        assertEquals(1, fks.size());
        assertEquals("customer_id", fks.get(0).get("column"));
        assertEquals("customers", fks.get(0).get("refTable"));

        Map<String, Object> resolved = fixture.service.resolveMetadataReference(1L, "app_a", "customers", "Customer Name");
        assertEquals("APP_A", resolved.get("schema"));
        assertEquals("customers", resolved.get("table"));
        assertEquals("Customer Name", resolved.get("column"));
        assertEquals("CHARACTER VARYING", resolved.get("columnType"));

        Map<String, Object> view = fixture.service.resolveMetadataReference(1L, "APP_A", "customer_view", "customer_id");
        assertEquals("VIEW", view.get("tableType"));
    }

    @Test
    void typedMistakesAmbiguityDriftAndUnsafeIdentifiersFailBeforeExecution() throws Exception {
        Fixture fixture = fixture();
        fixture.createCoreObjects();

        ApiException missingSchema = assertThrows(ApiException.class,
                () -> fixture.service.tables(1L, "missing_schema"));
        assertEquals(HttpStatus.NOT_FOUND, missingSchema.getStatus());
        assertTrue(missingSchema.getMessage().contains("schema"));

        ApiException missingTable = assertThrows(ApiException.class,
                () -> fixture.service.columns(1L, "APP_A", "customer"));
        assertEquals(HttpStatus.NOT_FOUND, missingTable.getStatus());
        assertTrue(missingTable.getMessage().contains("table or view"));

        ApiException missingColumn = assertThrows(ApiException.class,
                () -> fixture.service.resolveMetadataReference(1L, "APP_A", "customers", "customer_name"));
        assertEquals(HttpStatus.NOT_FOUND, missingColumn.getStatus());
        assertTrue(missingColumn.getMessage().contains("column"));

        ApiException unsafe = assertThrows(ApiException.class,
                () -> fixture.service.resolveMetadataReference(1L, "APP_A", "customers; DROP TABLE orders", "customer_id"));
        assertEquals(HttpStatus.BAD_REQUEST, unsafe.getStatus());
        assertFalse(unsafe.getMessage().toLowerCase().contains("stack"));

        fixture.drop("DROP TABLE \"APP_A\".\"orders\"");
        ApiException drift = assertThrows(ApiException.class,
                () -> fixture.service.resolveMetadataReference(1L, "APP_A", "orders", "order_id"));
        assertEquals(HttpStatus.NOT_FOUND, drift.getStatus());
        assertTrue(drift.getMessage().contains("orders"));
    }

    @Test
    void duplicateTableNamesAcrossSchemasRequireExplicitSchemaAndRoundTripSpecialIdentifiers() throws Exception {
        Fixture fixture = fixture();
        fixture.createCoreObjects();

        Map<String, Object> appA = fixture.service.resolveMetadataReference(1L, "APP_A", "customers", "名");
        Map<String, Object> appB = fixture.service.resolveMetadataReference(1L, "APP_B", "customers", "customer_id");
        assertEquals("APP_A", appA.get("schema"));
        assertEquals("名", appA.get("column"));
        assertEquals("APP_B", appB.get("schema"));

        Map<String, Object> reserved = fixture.service.resolveMetadataReference(1L, "APP_A", "select", "from");
        assertEquals("select", reserved.get("table"));
        assertEquals("from", reserved.get("column"));
    }

    private static Fixture fixture() {
        String db = "dsrc003_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:h2:mem:" + db + ";MODE=PostgreSQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1";
        DataSourceEntity ds = new DataSourceEntity();
        ds.setId(1L);
        ds.setName("DSRC-003-H2");
        ds.setKind("H2");
        ds.setJdbcUrl(url);
        ds.setUsername("sa");
        ds.setPassword("");
        ds.setRole("BOTH");

        DataSourceRepository repo = mock(DataSourceRepository.class);
        when(repo.findById(1L)).thenReturn(Optional.of(ds));
        OwnershipGuard ownership = mock(OwnershipGuard.class);
        DataSourceService service = new DataSourceService(repo, new ConnectionFactory(), mock(AuditService.class),
                ownership, mock(JdbcTemplate.class));
        return new Fixture(url, service);
    }

    private record Fixture(String url, DataSourceService service) {
        void createCoreObjects() throws Exception {
            try (var c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                st.execute("CREATE SCHEMA \"APP_A\"");
                st.execute("CREATE SCHEMA \"APP_B\"");
                st.execute("CREATE TABLE \"APP_A\".\"customers\" (\"customer_id\" INT PRIMARY KEY, \"Customer Name\" VARCHAR(80), \"名\" VARCHAR(40))");
                st.execute("CREATE TABLE \"APP_A\".\"orders\" (\"order_id\" INT PRIMARY KEY, \"customer_id\" INT, CONSTRAINT \"fk_orders_customer\" FOREIGN KEY (\"customer_id\") REFERENCES \"APP_A\".\"customers\"(\"customer_id\"))");
                st.execute("CREATE TABLE \"APP_A\".\"select\" (\"from\" VARCHAR(20))");
                st.execute("CREATE VIEW \"APP_A\".\"customer_view\" AS SELECT \"customer_id\", \"Customer Name\" FROM \"APP_A\".\"customers\"");
                st.execute("CREATE TABLE \"APP_B\".\"customers\" (\"customer_id\" INT PRIMARY KEY)");
            }
        }

        void drop(String sql) throws Exception {
            try (var c = DriverManager.getConnection(url, "sa", "");
                 Statement st = c.createStatement()) {
                st.execute(sql);
            }
        }
    }
}
