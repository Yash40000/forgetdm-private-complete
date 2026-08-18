package io.forgetdm.topology;

import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.AccessPrincipal;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;

@Service
public class TopologyDiscoveryService {
    private final TopologyService topologies;
    private final TopologyMetadataReader metadata;
    private final ConnectionFactory connections;
    private final ExecutorService executor;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public TopologyDiscoveryService(TopologyService topologies, TopologyMetadataReader metadata,
                                    ConnectionFactory connections, ExecutorService provisioningExecutor,
                                    JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.topologies = topologies;
        this.metadata = metadata;
        this.connections = connections;
        this.executor = provisioningExecutor;
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @PostConstruct
    void recoverInterruptedOperations() {
        jdbc.update("""
                UPDATE topology_discovery_operations
                   SET status = 'FAILED', finished_at = CURRENT_TIMESTAMP, percent = 0,
                       error_message = 'Application restarted before discovery completed',
                       message = 'Discovery interrupted by application restart'
                 WHERE status IN ('QUEUED', 'RUNNING')
                """);
    }

    public TopologyService.DiscoveryOperation start(long topologyId) {
        TopologyService.TopologyRow topology = topologies.requireWithSourceVisibility(topologyId);
        List<TopologyService.SourceBinding> bindings = topologies.sourceRows(topologyId);
        if (bindings.isEmpty()) throw ApiException.bad("Attach at least one source schema before discovery");
        Integer active = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM topology_discovery_operations
                         WHERE topology_id = ? AND status IN ('QUEUED', 'RUNNING')
                        """, Integer.class, topologyId);
        if (active != null && active > 0) {
            throw ApiException.conflict("A topology discovery operation is already running");
        }
        AccessPrincipal caller = AccessContext.current().orElse(null);
        String token = AccessContext.currentToken().orElse(null);
        String actor = caller == null ? "system" : caller.username();
        long operationId = topologies.insert("""
                        INSERT INTO topology_discovery_operations
                          (topology_id, status, total_sources, requested_by, message)
                        VALUES (?, 'QUEUED', ?, ?, 'Queued for metadata capture')
                        """, topologyId, bindings.size(), actor);
        topologies.audit().record(actor, "TOPOLOGY_DISCOVERY_QUEUED", "DATA_TOPOLOGY",
                "topology-discovery", String.valueOf(operationId), topology.name(), "SUCCESS",
                "sources=" + bindings.size(), null);
        executor.submit(() -> AccessContext.callAs(caller, token, () -> {
            run(operationId, topologyId, actor);
            return null;
        }));
        return operation(topologyId, operationId);
    }

    public TopologyService.DiscoveryOperation operation(long topologyId, long operationId) {
        topologies.requireWithSourceVisibility(topologyId);
        List<TopologyService.DiscoveryOperation> rows = jdbc.query("""
                        SELECT * FROM topology_discovery_operations
                         WHERE id = ? AND topology_id = ?
                        """, (rs, row) -> TopologyService.operation(rs), operationId, topologyId);
        if (rows.isEmpty()) throw ApiException.notFound("Topology discovery operation not found");
        return rows.get(0);
    }

    public TopologyService.DiscoveryOperation cancel(long topologyId, long operationId) {
        topologies.requireWithSourceVisibility(topologyId);
        int changed = jdbc.update("""
                        UPDATE topology_discovery_operations
                           SET cancel_requested = TRUE, message = 'Cancellation requested'
                         WHERE id = ? AND topology_id = ? AND status IN ('QUEUED', 'RUNNING')
                        """, operationId, topologyId);
        if (changed == 0) {
            TopologyService.DiscoveryOperation current = operation(topologyId, operationId);
            if (!Set.of("COMPLETED", "FAILED", "CANCELLED").contains(current.status())) {
                throw ApiException.conflict("Discovery operation cannot be cancelled");
            }
            return current;
        }
        topologies.audit().record(topologies.actor(), "TOPOLOGY_DISCOVERY_CANCEL_REQUESTED",
                "DATA_TOPOLOGY", "topology-discovery", String.valueOf(operationId), null,
                "SUCCESS", "Cancellation requested", null);
        return operation(topologyId, operationId);
    }

    private void run(long operationId, long topologyId, String actor) {
        updateOperation(operationId, "RUNNING", 1, "Opening attached source schemas", null, null, null);
        try {
            List<TopologyService.SourceBinding> bindings = topologies.sourceRows(topologyId);
            int completedSources = 0;
            int completedObjects = 0;
            for (TopologyService.SourceBinding binding : bindings) {
                checkCancelled(operationId);
                DataSourceEntity source = topologies.source(binding.dataSourceId());
                updatePosition(operationId, source.getName(), binding.schemaName(), null,
                        "Capturing " + source.getName() + " / " + binding.schemaName());
                TopologyMetadataReader.Capture capture;
                try (Connection connection = connections.openPooled(source)) {
                    int completedBefore = completedObjects;
                    capture = metadata.read(connection, source, binding.schemaName(),
                            () -> checkCancelled(operationId),
                            new TopologyMetadataReader.Progress() {
                                @Override
                                public void tablesDiscovered(int count) {
                                    jdbc.update("""
                                            UPDATE topology_discovery_operations
                                               SET total_objects = total_objects + ?,
                                                   message = ?
                                             WHERE id = ?
                                            """, count, "Discovered " + count + " objects in "
                                                    + source.getName() + " / " + binding.schemaName(), operationId);
                                }

                                @Override
                                public void columnsRead(int count) {
                                    if (count > 0 && count % 500 == 0) {
                                        jdbc.update("""
                                                UPDATE topology_discovery_operations
                                                   SET message = ? WHERE id = ?
                                                """, "Read " + count + " columns from " + source.getName(), operationId);
                                    }
                                }
                            });
                    completedObjects = completedBefore + capture.tables().size();
                }
                persistCapture(topologyId, operationId, binding, capture);
                completedSources++;
                int percent = Math.min(95, 5 + (int) Math.floor(completedSources * 90.0 / bindings.size()));
                jdbc.update("""
                                UPDATE topology_discovery_operations
                                   SET completed_sources = ?, completed_objects = ?, percent = ?,
                                       current_object = NULL, message = ?
                                 WHERE id = ?
                                """, completedSources, completedObjects, percent,
                        "Captured " + source.getName() + " / " + capture.schema(), operationId);
            }
            checkCancelled(operationId);
            activate(topologyId, operationId, actor);
        } catch (Cancelled ignored) {
            cleanupOperationGraph(operationId);
            updateOperation(operationId, "CANCELLED", 0, "Discovery cancelled", null, null, null);
            topologies.audit().record(actor, "TOPOLOGY_DISCOVERY_CANCELLED", "DATA_TOPOLOGY",
                    "topology-discovery", String.valueOf(operationId), null, "SUCCESS",
                    "No partial graph was activated", null);
        } catch (Exception failure) {
            cleanupOperationGraph(operationId);
            String message = rootMessage(failure);
            updateOperation(operationId, "FAILED", 0, "Discovery failed", message, null, null);
            topologies.audit().record(actor, "TOPOLOGY_DISCOVERY_FAILED", "DATA_TOPOLOGY",
                    "topology-discovery", String.valueOf(operationId), null, "FAILURE",
                    message, null);
        }
    }

    private void persistCapture(long topologyId, long operationId, TopologyService.SourceBinding binding,
                                TopologyMetadataReader.Capture capture) {
        checkCancelled(operationId);
        List<TopologyMetadataReader.TableDef> tables = capture.tables();
        jdbc.batchUpdate("""
                        INSERT INTO topology_nodes
                          (topology_id, operation_id, source_binding_id, stable_key, catalog_name,
                           schema_name, object_name, object_type, column_count, primary_key_count)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, tables, 250, (ps, table) -> {
            List<TopologyMetadataReader.ColumnDef> columns =
                    capture.columns().getOrDefault(tableKey(table.name()), List.of());
            ps.setLong(1, topologyId);
            ps.setLong(2, operationId);
            ps.setLong(3, binding.id());
            ps.setString(4, nodeStableKey(binding.id(), capture.schema(), table.name()));
            ps.setString(5, table.catalog());
            ps.setString(6, firstText(table.schema(), capture.schema()));
            ps.setString(7, table.name());
            ps.setString(8, table.type());
            ps.setInt(9, columns.size());
            ps.setInt(10, (int) columns.stream().filter(TopologyMetadataReader.ColumnDef::primaryKey).count());
        });

        Map<String, Long> nodeIds = new LinkedHashMap<>();
        jdbc.query("""
                        SELECT id, stable_key FROM topology_nodes
                         WHERE operation_id = ? AND source_binding_id = ?
                        """, (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> nodeIds.put(rs.getString("stable_key"), rs.getLong("id")),
                operationId, binding.id());

        List<ColumnInsert> columnRows = new ArrayList<>();
        for (TopologyMetadataReader.TableDef table : tables) {
            Long nodeId = nodeIds.get(nodeStableKey(binding.id(), capture.schema(), table.name()));
            if (nodeId == null) continue;
            for (TopologyMetadataReader.ColumnDef column :
                    capture.columns().getOrDefault(tableKey(table.name()), List.of())) {
                columnRows.add(new ColumnInsert(nodeId, column));
            }
        }
        jdbc.batchUpdate("""
                        INSERT INTO topology_columns
                          (node_id, ordinal_position, column_name, data_type, jdbc_type, length_value,
                           scale_value, nullable, primary_key, unique_key, generated_column, default_expression)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, columnRows, 500, (ps, row) -> {
            TopologyMetadataReader.ColumnDef column = row.column();
            ps.setLong(1, row.nodeId());
            ps.setInt(2, column.ordinal());
            ps.setString(3, column.name());
            ps.setString(4, column.typeName());
            ps.setInt(5, column.jdbcType());
            setNullable(ps, 6, column.length());
            setNullable(ps, 7, column.scale());
            ps.setBoolean(8, column.nullable());
            ps.setBoolean(9, column.primaryKey());
            ps.setBoolean(10, column.uniqueKey());
            ps.setBoolean(11, column.generated());
            ps.setString(12, column.defaultExpression());
        });

        List<EdgeInsert> edges = new ArrayList<>();
        for (TopologyMetadataReader.ForeignKeyDef fk : capture.foreignKeys()) {
            Long child = nodeIds.get(nodeStableKey(binding.id(), capture.schema(), fk.childTable()));
            Long parent = nodeIds.get(nodeStableKey(binding.id(), capture.schema(), fk.parentTable()));
            if (child == null || parent == null) continue;
            String stableKey = edgeStableKey(binding.id(), fk);
            String evidence = evidenceJson(fk);
            edges.add(new EdgeInsert(stableKey, fk.name(), child, parent,
                    String.join("|", fk.childColumns()), String.join("|", fk.parentColumns()), evidence));
        }
        jdbc.batchUpdate("""
                        INSERT INTO topology_edges
                          (topology_id, operation_id, source_binding_id, stable_key, constraint_name,
                           child_node_id, parent_node_id, child_columns, parent_columns,
                           evidence_type, decision_status, confidence, enabled, evidence_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'DB_FOREIGN_KEY', 'VERIFIED', 100, TRUE, ?)
                        """, edges, 250, (ps, edge) -> {
            ps.setLong(1, topologyId);
            ps.setLong(2, operationId);
            ps.setLong(3, binding.id());
            ps.setString(4, edge.stableKey());
            ps.setString(5, edge.name());
            ps.setLong(6, edge.childNodeId());
            ps.setLong(7, edge.parentNodeId());
            ps.setString(8, edge.childColumns());
            ps.setString(9, edge.parentColumns());
            ps.setString(10, edge.evidenceJson());
        });

        jdbc.update("""
                        UPDATE topology_sources
                           SET provider_mode = ?, captured_at = CURRENT_TIMESTAMP,
                               node_count = ?, edge_count = ?
                         WHERE id = ?
                        """, capture.providerMode(), tables.size(), edges.size(), binding.id());
    }

    private void activate(long topologyId, long operationId, String actor) {
        String hash = canonicalHash(operationId);
        int nodeCount = count("topology_nodes", operationId);
        int edgeCount = count("topology_edges", operationId);
        transactions.executeWithoutResult(status -> {
            TopologyService.TopologyRow current = topologies.require(topologyId);
            int version = current.currentVersion() + 1;
            String summary = "{\"provider\":\"bulk-metadata\",\"nodes\":" + nodeCount
                    + ",\"relationships\":" + edgeCount + "}";
            topologies.insert("""
                            INSERT INTO topology_versions
                              (topology_id, operation_id, version_number, content_hash,
                               node_count, edge_count, summary_json, created_by)
                            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                            """, topologyId, operationId, version, hash, nodeCount, edgeCount, summary, actor);
            jdbc.update("""
                            UPDATE topology_models
                               SET current_operation_id = ?, current_hash = ?, current_version = ?,
                                   status = 'ACTIVE', lock_version = lock_version + 1,
                                   updated_at = CURRENT_TIMESTAMP
                             WHERE id = ?
                            """, operationId, hash, version, topologyId);
            jdbc.update("""
                            UPDATE topology_discovery_operations
                               SET status = 'COMPLETED', percent = 100, finished_at = CURRENT_TIMESTAMP,
                                   completed_objects = total_objects, current_source = NULL,
                                   active_schema = NULL, current_object = NULL,
                                   message = ?
                             WHERE id = ?
                            """, "Topology version " + version + " captured: " + nodeCount + " objects, "
                    + edgeCount + " relationships", operationId);
        });
        topologies.audit().record(actor, "TOPOLOGY_DISCOVERY_COMPLETED", "DATA_TOPOLOGY",
                "topology-discovery", String.valueOf(operationId), null, "SUCCESS",
                "hash=" + hash + " nodes=" + nodeCount + " edges=" + edgeCount, null);
    }

    /**
     * Stable graph fingerprint. Capture time, operation id, row estimates and progress are
     * deliberately absent: the same logical schema produces the same hash on every refresh.
     */
    String canonicalHash(long operationId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            jdbc.query("""
                            SELECT n.stable_key, n.object_type, c.ordinal_position, c.column_name,
                                   c.data_type, c.length_value, c.scale_value, c.nullable,
                                   c.primary_key, c.unique_key, c.generated_column
                              FROM topology_nodes n
                              LEFT JOIN topology_columns c ON c.node_id = n.id
                             WHERE n.operation_id = ?
                             ORDER BY n.stable_key, c.ordinal_position, c.column_name
                            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String line = "N|" + rs.getString("stable_key") + "|" + rs.getString("object_type")
                        + "|" + rs.getInt("ordinal_position") + "|" + Objects.toString(rs.getString("column_name"), "")
                        + "|" + Objects.toString(rs.getString("data_type"), "")
                        + "|" + Objects.toString(rs.getObject("length_value"), "")
                        + "|" + Objects.toString(rs.getObject("scale_value"), "")
                        + "|" + rs.getBoolean("nullable") + "|" + rs.getBoolean("primary_key")
                        + "|" + rs.getBoolean("unique_key") + "|" + rs.getBoolean("generated_column") + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }, operationId);
            jdbc.query("""
                            SELECT stable_key, evidence_type, decision_status, confidence, enabled
                              FROM topology_edges WHERE operation_id = ? ORDER BY stable_key
                            """, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                String line = "E|" + rs.getString("stable_key") + "|" + rs.getString("evidence_type")
                        + "|" + rs.getString("decision_status") + "|" + rs.getInt("confidence")
                        + "|" + rs.getBoolean("enabled") + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }, operationId);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
        } catch (Exception e) {
            throw new IllegalStateException("Unable to fingerprint topology graph", e);
        }
    }

    private int count(String table, long operationId) {
        // Table names are constants controlled by this class, never request input.
        Integer value = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE operation_id = ?",
                Integer.class, operationId);
        return value == null ? 0 : value;
    }

    private void cleanupOperationGraph(long operationId) {
        jdbc.update("DELETE FROM topology_edges WHERE operation_id = ?", operationId);
        jdbc.update("DELETE FROM topology_nodes WHERE operation_id = ?", operationId);
    }

    private void checkCancelled(long operationId) {
        Boolean requested = jdbc.queryForObject("""
                        SELECT cancel_requested FROM topology_discovery_operations WHERE id = ?
                        """, Boolean.class, operationId);
        if (Boolean.TRUE.equals(requested) || Thread.currentThread().isInterrupted()) throw new Cancelled();
    }

    private void updatePosition(long operationId, String source, String schema, String object, String message) {
        jdbc.update("""
                        UPDATE topology_discovery_operations
                           SET current_source = ?, active_schema = ?, current_object = ?, message = ?
                         WHERE id = ?
                        """, source, schema, object, message, operationId);
    }

    private void updateOperation(long operationId, String status, int percent, String message,
                                 String error, String source, String schema) {
        boolean terminal = Set.of("COMPLETED", "FAILED", "CANCELLED").contains(status);
        jdbc.update("""
                        UPDATE topology_discovery_operations
                           SET status = ?, percent = ?, message = ?, error_message = ?,
                               current_source = COALESCE(?, current_source),
                               active_schema = COALESCE(?, active_schema),
                               started_at = CASE WHEN ? = 'RUNNING' AND started_at IS NULL
                                                 THEN CURRENT_TIMESTAMP ELSE started_at END,
                               finished_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE finished_at END
                         WHERE id = ?
                        """, status, percent, message, error, source, schema, status, terminal, operationId);
    }

    private String evidenceJson(TopologyMetadataReader.ForeignKeyDef fk) {
        try {
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("rule", "DECLARED_FOREIGN_KEY");
            evidence.put("source", "DB_CATALOG");
            evidence.put("confidence", 100);
            evidence.put("explanation", "The source database declares this foreign-key constraint.");
            evidence.put("constraint", fk.name());
            evidence.put("childColumns", fk.childColumns());
            evidence.put("parentColumns", fk.parentColumns());
            return topologies.json().writeValueAsString(evidence);
        } catch (Exception e) {
            return "{\"rule\":\"DECLARED_FOREIGN_KEY\",\"source\":\"DB_CATALOG\",\"confidence\":100}";
        }
    }

    private static void setNullable(PreparedStatement ps, int index, Number value) throws java.sql.SQLException {
        if (value == null) ps.setObject(index, null);
        else ps.setObject(index, value);
    }

    private static String nodeStableKey(long bindingId, String schema, String table) {
        return (bindingId + "|" + Objects.toString(schema, "") + "|" + Objects.toString(table, ""))
                .toLowerCase(Locale.ROOT);
    }

    private static String edgeStableKey(long bindingId, TopologyMetadataReader.ForeignKeyDef fk) {
        return (bindingId + "|" + Objects.toString(fk.name(), "") + "|" + fk.childTable()
                + "|" + String.join(",", fk.childColumns()) + "|" + fk.parentTable()
                + "|" + String.join(",", fk.parentColumns())).toLowerCase(Locale.ROOT);
    }

    private static String tableKey(String table) {
        return table == null ? "" : table.toLowerCase(Locale.ROOT);
    }

    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        if (message == null || message.isBlank()) message = current.getClass().getSimpleName();
        return message.length() <= 1900 ? message : message.substring(0, 1900);
    }

    private record ColumnInsert(long nodeId, TopologyMetadataReader.ColumnDef column) {}
    private record EdgeInsert(String stableKey, String name, long childNodeId, long parentNodeId,
                              String childColumns, String parentColumns, String evidenceJson) {}
    private static final class Cancelled extends RuntimeException {}
}
