package io.forgetdm.topology;

import io.forgetdm.datasource.ConnectionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.mockito.Mockito.mock;

class TopologyCanonicalHashTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void closeExecutor() {
        executor.shutdownNow();
    }

    @Test
    void fingerprintIgnoresVolatileFactsButChangesWithSchemaIntent() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:topology-hash;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("""
                CREATE TABLE topology_nodes (
                    id BIGINT PRIMARY KEY,
                    operation_id BIGINT,
                    stable_key VARCHAR(200),
                    object_type VARCHAR(30),
                    row_estimate BIGINT
                )
                """);
        jdbc.execute("""
                CREATE TABLE topology_columns (
                    id BIGINT PRIMARY KEY,
                    node_id BIGINT,
                    ordinal_position INT,
                    column_name VARCHAR(100),
                    data_type VARCHAR(100),
                    length_value BIGINT,
                    scale_value INT,
                    nullable BOOLEAN,
                    primary_key BOOLEAN,
                    unique_key BOOLEAN,
                    generated_column BOOLEAN
                )
                """);
        jdbc.execute("""
                CREATE TABLE topology_edges (
                    stable_key VARCHAR(200),
                    operation_id BIGINT,
                    evidence_type VARCHAR(40),
                    decision_status VARCHAR(30),
                    confidence INT,
                    enabled BOOLEAN
                )
                """);
        jdbc.update("INSERT INTO topology_nodes VALUES (1, 10, '1|bank|customer', 'TABLE', 1000)");
        jdbc.update("""
                INSERT INTO topology_columns
                  VALUES (1, 1, 1, 'CUSTOMER_ID', 'BIGINT', 19, 0, FALSE, TRUE, TRUE, FALSE)
                """);
        jdbc.update("""
                INSERT INTO topology_edges
                  VALUES ('fk|customer', 10, 'DB_FOREIGN_KEY', 'VERIFIED', 100, TRUE)
                """);

        TopologyDiscoveryService service = new TopologyDiscoveryService(
                mock(TopologyService.class), mock(TopologyMetadataReader.class),
                mock(ConnectionFactory.class), executor, jdbc, mock(PlatformTransactionManager.class));

        String original = service.canonicalHash(10);
        jdbc.update("UPDATE topology_nodes SET row_estimate = 999999 WHERE id = 1");
        assertEquals(original, service.canonicalHash(10), "row estimates are observational, not topology intent");

        jdbc.update("UPDATE topology_columns SET data_type = 'VARCHAR' WHERE id = 1");
        assertNotEquals(original, service.canonicalHash(10), "schema changes must produce a new content hash");
    }
}
