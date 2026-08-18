package io.forgetdm.query;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QueryServiceTest {
    private final String url = "jdbc:h2:mem:query_service_" + System.nanoTime()
            + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private DataSourceService dataSources;
    private ConnectionFactory connections;
    private QueryService service;
    private DataSourceEntity source;

    @BeforeEach
    void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(url, "sa", "")) {
            c.createStatement().execute("create table explorer_rows (id bigint primary key, name varchar(80) not null, quantity integer)");
            c.createStatement().execute("insert into explorer_rows values (1, 'Alpha', 1), (2, 'Beta', 2)");
            c.createStatement().execute("create table no_key (name varchar(80))");
            c.createStatement().execute("insert into no_key values ('read-only')");
        }

        source = new DataSourceEntity();
        source.setId(7L);
        source.setName("test-h2");
        source.setKind("H2");
        source.setRole("BOTH");
        source.setJdbcUrl(url);
        source.setUsername("sa");
        source.setPassword("");

        dataSources = mock(DataSourceService.class);
        connections = mock(ConnectionFactory.class);
        AuditService audit = mock(AuditService.class);
        when(dataSources.get(7L)).thenReturn(source);
        when(dataSources.resolveMetadataReference(eq(7L), anyString(), anyString(), isNull()))
                .thenAnswer(call -> Map.of("schema", "public", "table", call.getArgument(2)));
        when(dataSources.columns(eq(7L), eq("public"), anyString()))
                .thenAnswer(call -> columns((String) call.getArgument(2)));
        when(connections.openPooled(source)).thenAnswer(call -> DriverManager.getConnection(url, "sa", ""));
        when(connections.open(source)).thenAnswer(call -> DriverManager.getConnection(url, "sa", ""));
        service = new QueryService(dataSources, connections, audit);
    }

    @Test
    void tableCrudUsesPhysicalPrimaryKeyAndExactlyOneRow() throws Exception {
        Map<String, Object> first = service.readTable(
                new QueryController.TableReadRequest(7L, "public", "explorer_rows", 100, 0));
        assertEquals(2, first.get("rowCount"));
        assertEquals(List.of("id"), first.get("primaryKeys"));
        assertEquals(true, first.get("editable"));

        service.insert(new QueryController.TableInsertRequest(
                7L, "public", "explorer_rows", Map.of("id", "3", "name", "Gamma", "quantity", "3")));
        service.update(new QueryController.TableUpdateRequest(
                7L, "public", "explorer_rows", Map.of("id", 1), Map.of("quantity", "9")));
        service.delete(new QueryController.TableDeleteRequest(
                7L, "public", "explorer_rows", Map.of("id", 2)));

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             var rs = c.createStatement().executeQuery("select id, name, quantity from explorer_rows order by id")) {
            assertTrue(rs.next());
            assertEquals(1L, rs.getLong("id"));
            assertEquals(9, rs.getInt("quantity"));
            assertTrue(rs.next());
            assertEquals(3L, rs.getLong("id"));
            assertEquals("Gamma", rs.getString("name"));
            assertFalse(rs.next());
        }
    }

    @Test
    void updateRejectsTablesWithoutPrimaryKey() {
        ApiException error = assertThrows(ApiException.class, () -> service.update(
                new QueryController.TableUpdateRequest(
                        7L, "public", "no_key", Map.of("name", "read-only"), Map.of("name", "changed"))));
        assertTrue(error.getMessage().contains("require a database primary key"));
    }

    @Test
    void governedSqlConsoleSupportsDdlAndDmlButRejectsMultipleStatements() throws Exception {
        Map<String, Object> created = service.execute(
                7L, "create table console_created (id bigint primary key, note varchar(80))");
        assertEquals("CREATE", created.get("statementType"));

        Map<String, Object> inserted = service.execute(
                7L, "insert into console_created values (1, 'semicolon; inside value');");
        assertEquals("INSERT", inserted.get("statementType"));
        assertEquals(1, inserted.get("affectedRows"));

        try (Connection c = DriverManager.getConnection(url, "sa", "");
             var rs = c.createStatement().executeQuery("select note from console_created where id=1")) {
            assertTrue(rs.next());
            assertEquals("semicolon; inside value", rs.getString(1));
        }

        ApiException error = assertThrows(ApiException.class,
                () -> service.execute(7L, "update explorer_rows set quantity=2; delete from explorer_rows"));
        assertTrue(error.getMessage().contains("Only one SQL statement"));
    }

    private static List<Map<String, Object>> columns(String table) {
        if ("no_key".equals(table)) {
            return List.of(column("name", Types.VARCHAR, false));
        }
        return List.of(
                column("id", Types.BIGINT, false),
                column("name", Types.VARCHAR, false),
                column("quantity", Types.INTEGER, true)
        );
    }

    private static Map<String, Object> column(String name, int jdbcType, boolean nullable) {
        return Map.of(
                "column", name,
                "type", jdbcType == Types.VARCHAR ? "VARCHAR" : "NUMERIC",
                "jdbcType", jdbcType,
                "nullable", nullable,
                "generated", false,
                "autoIncrement", false
        );
    }
}
