package io.forgetdm.topology;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.security.OwnershipGuard;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class TopologyService {
    private static final Set<String> VISIBILITIES = Set.of("PRIVATE", "GROUP", "SHARED");
    private static final Set<String> EDGE_DECISIONS = Set.of("VERIFIED", "REJECTED", "DISABLED");

    private final JdbcTemplate jdbc;
    private final DataSourceService dataSources;
    private final OwnershipGuard ownership;
    private final AuditService audit;
    private final ObjectMapper json;

    public TopologyService(JdbcTemplate jdbc, DataSourceService dataSources, OwnershipGuard ownership,
                           AuditService audit, ObjectMapper json) {
        this.jdbc = jdbc;
        this.dataSources = dataSources;
        this.ownership = ownership;
        this.audit = audit;
        this.json = json;
    }

    public List<TopologySummary> list() {
        return jdbc.query("""
                        SELECT t.id, t.name, t.domain, t.description, t.status, t.current_hash,
                               t.current_version, t.lock_version, t.owner_user_id, t.owner_username,
                               t.owner_group_id, t.visibility, t.created_at, t.updated_at,
                               (SELECT COUNT(*) FROM topology_sources s WHERE s.topology_id = t.id) source_count,
                               (SELECT COUNT(*) FROM topology_nodes n
                                  WHERE n.topology_id = t.id AND n.operation_id = t.current_operation_id) node_count,
                               (SELECT COUNT(*) FROM topology_edges e
                                  WHERE e.topology_id = t.id AND e.operation_id = t.current_operation_id
                                    AND e.enabled = TRUE) edge_count
                          FROM topology_models t
                         ORDER BY t.updated_at DESC, LOWER(t.name)
                        """,
                (rs, row) -> new TopologySummary(
                        rs.getLong("id"), rs.getString("name"), rs.getString("domain"),
                        rs.getString("description"), rs.getString("status"), rs.getString("current_hash"),
                        rs.getInt("current_version"), rs.getLong("lock_version"),
                        rs.getInt("source_count"), rs.getInt("node_count"), rs.getInt("edge_count"),
                        rs.getString("owner_username"), rs.getString("visibility"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at")),
                        nullableLong(rs, "owner_user_id"), nullableLong(rs, "owner_group_id")))
                .stream()
                .filter(row -> ownership.canSee(row.ownerUserId(), row.ownerGroupId(), row.visibility()))
                .toList();
    }

    @Transactional
    public TopologySummary create(CreateTopology request) {
        String name = validName(request == null ? null : request.name());
        String domain = trim(request == null ? null : request.domain(), 80);
        String description = trim(request == null ? null : request.description(), 1000);
        String visibility = normalizeVisibility(request == null ? null : request.visibility());
        boolean duplicate = list().stream().anyMatch(existing -> existing.name().equalsIgnoreCase(name));
        if (duplicate) throw ApiException.conflict("A visible topology named '" + name + "' already exists");

        long id = insert("""
                        INSERT INTO topology_models
                          (name, domain, description, owner_user_id, owner_username, owner_group_id, visibility)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """,
                name, domain, description, ownership.defaultOwnerUserId(), ownership.defaultOwnerUsername(),
                ownership.defaultOwnerGroupId(), visibility);
        audit.record(ownership.defaultOwnerUsername(), "TOPOLOGY_CREATED", "DATA_TOPOLOGY",
                "topology", String.valueOf(id), name, "SUCCESS", "domain=" + Objects.toString(domain, ""), null);
        return get(id);
    }

    @Transactional
    public TopologySummary update(long id, UpdateTopology request) {
        TopologyRow current = require(id);
        if (request == null || request.lockVersion() == null || request.lockVersion() != current.lockVersion()) {
            throw ApiException.conflict("This topology changed after you opened it. Refresh and retry.");
        }
        String name = request.name() == null ? current.name() : validName(request.name());
        String domain = request.domain() == null ? current.domain() : trim(request.domain(), 80);
        String description = request.description() == null
                ? current.description() : trim(request.description(), 1000);
        String visibility = request.visibility() == null
                ? current.visibility() : normalizeVisibility(request.visibility());
        int changed = jdbc.update("""
                        UPDATE topology_models
                           SET name = ?, domain = ?, description = ?, visibility = ?,
                               lock_version = lock_version + 1, updated_at = CURRENT_TIMESTAMP
                         WHERE id = ? AND lock_version = ?
                        """,
                name, domain, description, visibility, id, current.lockVersion());
        if (changed != 1) throw ApiException.conflict("This topology was updated by another user");
        audit.record(actor(), "TOPOLOGY_UPDATED", "DATA_TOPOLOGY", "topology", String.valueOf(id), name,
                "SUCCESS", "metadata updated", null);
        return get(id);
    }

    public TopologySummary get(long id) {
        TopologyRow row = require(id);
        return jdbc.queryForObject("""
                        SELECT (SELECT COUNT(*) FROM topology_sources s WHERE s.topology_id = t.id) source_count,
                               (SELECT COUNT(*) FROM topology_nodes n
                                  WHERE n.topology_id = t.id AND n.operation_id = t.current_operation_id) node_count,
                               (SELECT COUNT(*) FROM topology_edges e
                                  WHERE e.topology_id = t.id AND e.operation_id = t.current_operation_id
                                    AND e.enabled = TRUE) edge_count
                          FROM topology_models t WHERE t.id = ?
                        """,
                (rs, ignored) -> new TopologySummary(row.id(), row.name(), row.domain(), row.description(),
                        row.status(), row.currentHash(), row.currentVersion(), row.lockVersion(),
                        rs.getInt("source_count"), rs.getInt("node_count"), rs.getInt("edge_count"),
                        row.ownerUsername(), row.visibility(), row.createdAt(), row.updatedAt(),
                        row.ownerUserId(), row.ownerGroupId()), id);
    }

    @Transactional
    public void delete(long id) {
        TopologyRow topology = require(id);
        jdbc.update("DELETE FROM topology_models WHERE id = ?", id);
        audit.record(actor(), "TOPOLOGY_DELETED", "DATA_TOPOLOGY", "topology", String.valueOf(id),
                topology.name(), "SUCCESS", "Governed topology and operation snapshots deleted", null);
    }

    public List<SourceBinding> sources(long topologyId) {
        require(topologyId);
        List<SourceBinding> rows = sourceRows(topologyId);
        // A shared topology must not become a side channel into a source the caller cannot browse.
        for (SourceBinding row : rows) dataSources.get(row.dataSourceId());
        return rows;
    }

    @Transactional
    public SourceBinding attachSource(long topologyId, AttachSource request) {
        TopologyRow topology = require(topologyId);
        if (request == null || request.dataSourceId() == null) throw ApiException.bad("Data source is required");
        DataSourceEntity source = dataSources.getSourceCapable(request.dataSourceId());
        String schema = trim(request.schemaName(), 256);
        if (schema == null) throw ApiException.bad("Schema is required");
        boolean schemaExists = dataSources.schemas(source.getId()).stream()
                .map(row -> String.valueOf(row.get("schema")))
                .anyMatch(candidate -> candidate.equalsIgnoreCase(schema));
        if (!schemaExists) {
            throw ApiException.bad("Schema '" + schema + "' was not found on data source '" + source.getName() + "'");
        }
        boolean exists = sourceRows(topologyId).stream().anyMatch(row ->
                row.dataSourceId().equals(source.getId()) && row.schemaName().equalsIgnoreCase(schema));
        if (exists) throw ApiException.conflict("That data source and schema are already attached");
        long id = insert("""
                        INSERT INTO topology_sources
                          (topology_id, data_source_id, schema_name, application_label)
                        VALUES (?, ?, ?, ?)
                        """,
                topologyId, source.getId(), schema, trim(request.applicationLabel(), 120));
        touch(topologyId);
        audit.record(actor(), "TOPOLOGY_SOURCE_ATTACHED", "DATA_TOPOLOGY", "topology-source",
                String.valueOf(id), source.getName(), "SUCCESS",
                "topology=" + topology.name() + " schema=" + schema, null);
        return sourceRows(topologyId).stream().filter(row -> row.id() == id).findFirst().orElseThrow();
    }

    @Transactional
    public void detachSource(long topologyId, long bindingId) {
        TopologyRow topology = require(topologyId);
        SourceBinding binding = sourceRows(topologyId).stream().filter(row -> row.id() == bindingId)
                .findFirst().orElseThrow(() -> ApiException.notFound("Topology source not found"));
        dataSources.get(binding.dataSourceId());
        jdbc.update("DELETE FROM topology_sources WHERE id = ? AND topology_id = ?", bindingId, topologyId);
        touch(topologyId);
        audit.record(actor(), "TOPOLOGY_SOURCE_DETACHED", "DATA_TOPOLOGY", "topology-source",
                String.valueOf(bindingId), binding.dataSourceName(), "SUCCESS",
                "topology=" + topology.name() + " schema=" + binding.schemaName(), null);
    }

    public DiscoveryOperation latestOperation(long topologyId) {
        requireWithSourceVisibility(topologyId);
        List<DiscoveryOperation> rows = jdbc.query("""
                        SELECT * FROM topology_discovery_operations
                         WHERE topology_id = ? ORDER BY created_at DESC LIMIT 1
                        """, (rs, row) -> operation(rs), topologyId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public GraphSnapshot graph(long topologyId, String query, Long sourceBindingId, Integer limit) {
        TopologyRow topology = requireWithSourceVisibility(topologyId);
        if (topology.currentOperationId() == null) return new GraphSnapshot(List.of(), List.of(), 0, 0, false);
        return graphForOperation(topologyId, topology.currentOperationId(), query, sourceBindingId, limit);
    }

    public GraphSnapshot graphVersion(long topologyId, int versionNumber, String query,
                                      Long sourceBindingId, Integer limit) {
        requireWithSourceVisibility(topologyId);
        List<Long> operations = jdbc.query("""
                        SELECT operation_id
                          FROM topology_versions
                         WHERE topology_id = ? AND version_number = ?
                        """, (rs, row) -> rs.getLong("operation_id"), topologyId, versionNumber);
        if (operations.isEmpty()) {
            throw ApiException.notFound("Topology version " + versionNumber + " not found");
        }
        return graphForOperation(topologyId, operations.get(0), query, sourceBindingId, limit);
    }

    private GraphSnapshot graphForOperation(long topologyId, long operationId, String query,
                                            Long sourceBindingId, Integer limit) {
        int safeLimit = Math.max(25, Math.min(limit == null ? 250 : limit, 1000));
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<GraphNode> allNodes = jdbc.query("""
                        SELECT n.id, n.source_binding_id, s.application_label, ds.name data_source_name,
                               n.schema_name, n.object_name, n.object_type, n.column_count,
                               n.primary_key_count, n.row_estimate
                          FROM topology_nodes n
                          JOIN topology_sources s ON s.id = n.source_binding_id
                          JOIN data_sources ds ON ds.id = s.data_source_id
                         WHERE n.topology_id = ? AND n.operation_id = ?
                         ORDER BY COALESCE(s.application_label, ds.name), n.schema_name, n.object_name
                        """,
                (rs, row) -> new GraphNode(rs.getLong("id"), rs.getLong("source_binding_id"),
                        firstText(rs.getString("application_label"), rs.getString("data_source_name")),
                        rs.getString("schema_name"), rs.getString("object_name"), rs.getString("object_type"),
                        rs.getInt("column_count"), rs.getInt("primary_key_count"),
                        nullableLong(rs, "row_estimate")), topologyId, operationId);
        List<GraphNode> filtered = allNodes.stream()
                .filter(node -> sourceBindingId == null || sourceBindingId.equals(node.sourceBindingId()))
                .filter(node -> q.isEmpty() || (node.application() + "." + node.schema() + "." + node.name())
                        .toLowerCase(Locale.ROOT).contains(q))
                .limit(safeLimit)
                .toList();
        Set<Long> nodeIds = filtered.stream().map(GraphNode::id).collect(java.util.stream.Collectors.toSet());
        List<GraphEdge> allEdges = jdbc.query("""
                        SELECT id, constraint_name, child_node_id, parent_node_id, child_columns,
                               parent_columns, evidence_type, decision_status, confidence, enabled, evidence_json
                          FROM topology_edges
                         WHERE topology_id = ? AND operation_id = ?
                         ORDER BY constraint_name, id
                        """,
                (rs, row) -> new GraphEdge(rs.getLong("id"), rs.getString("constraint_name"),
                        rs.getLong("child_node_id"), rs.getLong("parent_node_id"),
                        splitColumns(rs.getString("child_columns")), splitColumns(rs.getString("parent_columns")),
                        rs.getString("evidence_type"), rs.getString("decision_status"),
                        rs.getInt("confidence"), rs.getBoolean("enabled"), rs.getString("evidence_json")),
                topologyId, operationId);
        List<GraphEdge> edges = allEdges.stream()
                .filter(edge -> nodeIds.contains(edge.childNodeId()) && nodeIds.contains(edge.parentNodeId()))
                .toList();
        return new GraphSnapshot(filtered, edges, allNodes.size(), allEdges.size(), filtered.size() < allNodes.size());
    }

    public List<ColumnSnapshot> columns(long topologyId, long nodeId) {
        TopologyRow topology = requireWithSourceVisibility(topologyId);
        if (topology.currentOperationId() == null) return List.of();
        return jdbc.query("""
                        SELECT c.id, c.ordinal_position, c.column_name, c.data_type, c.jdbc_type,
                               c.length_value, c.scale_value, c.nullable, c.primary_key, c.unique_key,
                               c.generated_column, c.default_expression
                          FROM topology_columns c
                          JOIN topology_nodes n ON n.id = c.node_id
                         WHERE n.id = ? AND n.topology_id = ? AND n.operation_id = ?
                         ORDER BY c.ordinal_position
                        """,
                (rs, row) -> new ColumnSnapshot(rs.getLong("id"), rs.getInt("ordinal_position"),
                        rs.getString("column_name"), rs.getString("data_type"), rs.getInt("jdbc_type"),
                        nullableLong(rs, "length_value"), nullableInteger(rs, "scale_value"),
                        rs.getBoolean("nullable"), rs.getBoolean("primary_key"), rs.getBoolean("unique_key"),
                        rs.getBoolean("generated_column"), rs.getString("default_expression")),
                nodeId, topologyId, topology.currentOperationId());
    }

    @Transactional
    public GraphEdge reviewEdge(long topologyId, long edgeId, EdgeDecision decision) {
        TopologyRow topology = requireWithSourceVisibility(topologyId);
        String status = decision == null ? null : upper(decision.status());
        if (!EDGE_DECISIONS.contains(status)) throw ApiException.bad("Status must be VERIFIED, REJECTED or DISABLED");
        int changed = jdbc.update("""
                        UPDATE topology_edges
                           SET decision_status = ?, enabled = ?, reviewed_by = ?, reviewed_at = CURRENT_TIMESTAMP
                         WHERE id = ? AND topology_id = ? AND operation_id = ?
                        """, status, !"DISABLED".equals(status) && !"REJECTED".equals(status), actor(),
                edgeId, topologyId, topology.currentOperationId());
        if (changed != 1) throw ApiException.notFound("Relationship not found in the current topology");
        audit.record(actor(), "TOPOLOGY_RELATIONSHIP_REVIEWED", "DATA_TOPOLOGY", "topology-edge",
                String.valueOf(edgeId), topology.name(), "SUCCESS", "decision=" + status, null);
        return graph(topologyId, null, null, 1000).edges().stream()
                .filter(edge -> edge.id() == edgeId).findFirst()
                .orElseThrow(() -> ApiException.notFound("Relationship not found"));
    }

    public List<TopologyVersion> versions(long topologyId) {
        requireWithSourceVisibility(topologyId);
        return jdbc.query("""
                        SELECT id, version_number, content_hash, node_count, edge_count,
                               summary_json, created_by, created_at
                          FROM topology_versions WHERE topology_id = ?
                         ORDER BY version_number DESC
                        """,
                (rs, row) -> new TopologyVersion(rs.getLong("id"), rs.getInt("version_number"),
                        rs.getString("content_hash"), rs.getInt("node_count"), rs.getInt("edge_count"),
                        rs.getString("summary_json"), rs.getString("created_by"),
                        instant(rs.getTimestamp("created_at"))), topologyId);
    }

    TopologyRow require(long id) {
        List<TopologyRow> rows = jdbc.query("SELECT * FROM topology_models WHERE id = ?",
                (rs, row) -> new TopologyRow(
                        rs.getLong("id"), rs.getString("name"), rs.getString("domain"),
                        rs.getString("description"), rs.getString("status"),
                        nullableLong(rs, "current_operation_id"), rs.getString("current_hash"),
                        rs.getInt("current_version"), rs.getLong("lock_version"),
                        nullableLong(rs, "owner_user_id"), rs.getString("owner_username"),
                        nullableLong(rs, "owner_group_id"), rs.getString("visibility"),
                        instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("updated_at"))), id);
        if (rows.isEmpty()) throw ApiException.notFound("Topology " + id + " not found");
        TopologyRow row = rows.get(0);
        ownership.assertCanSee("topology", id, row.ownerUserId(), row.ownerGroupId(), row.visibility());
        return row;
    }

    TopologyRow requireWithSourceVisibility(long id) {
        TopologyRow row = require(id);
        for (SourceBinding binding : sourceRows(id)) dataSources.get(binding.dataSourceId());
        return row;
    }

    List<SourceBinding> sourceRows(long topologyId) {
        return jdbc.query("""
                        SELECT s.id, s.topology_id, s.data_source_id, ds.name data_source_name,
                               ds.kind engine, s.schema_name, s.application_label, s.provider_mode,
                               s.captured_at, s.node_count, s.edge_count
                          FROM topology_sources s
                          JOIN data_sources ds ON ds.id = s.data_source_id
                         WHERE s.topology_id = ?
                         ORDER BY COALESCE(s.application_label, ds.name), s.schema_name
                        """,
                (rs, row) -> new SourceBinding(rs.getLong("id"), rs.getLong("topology_id"),
                        rs.getLong("data_source_id"), rs.getString("data_source_name"),
                        rs.getString("engine"), rs.getString("schema_name"),
                        rs.getString("application_label"), rs.getString("provider_mode"),
                        instant(rs.getTimestamp("captured_at")), rs.getInt("node_count"),
                        rs.getInt("edge_count")), topologyId);
    }

    DataSourceEntity source(long id) {
        return dataSources.getSourceCapable(id);
    }

    void touch(long topologyId) {
        jdbc.update("""
                UPDATE topology_models
                   SET lock_version = lock_version + 1, updated_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, topologyId);
    }

    JdbcTemplate jdbc() {
        return jdbc;
    }

    ObjectMapper json() {
        return json;
    }

    String actor() {
        return ownership.caller().map(p -> p.username()).orElse("system");
    }

    AuditService audit() {
        return audit;
    }

    long insert(String sql, Object... args) {
        KeyHolder key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, new String[]{"id"});
            for (int index = 0; index < args.length; index++) ps.setObject(index + 1, args[index]);
            return ps;
        }, key);
        Number value = key.getKey();
        if (value == null) throw new IllegalStateException("Database did not return the generated identifier");
        return value.longValue();
    }

    private static String validName(String value) {
        String name = trim(value, 120);
        if (name == null || name.length() < 8) {
            throw ApiException.bad("Topology name must be between 8 and 120 characters");
        }
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9 _.-]*")) {
            throw ApiException.bad("Topology name may contain letters, numbers, spaces, dots, underscores and hyphens");
        }
        return name;
    }

    private static String normalizeVisibility(String value) {
        String visibility = upper(value);
        if (visibility == null) return OwnershipGuard.GROUP;
        if (!VISIBILITIES.contains(visibility)) throw ApiException.bad("Visibility must be PRIVATE, GROUP or SHARED");
        return visibility;
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String result = value.trim();
        if (result.length() > max) throw ApiException.bad("Value exceeds the maximum length of " + max);
        return result;
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    private static List<String> splitColumns(String value) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split("\\|", -1));
    }

    public record CreateTopology(String name, String domain, String description, String visibility) {}
    public record UpdateTopology(String name, String domain, String description, String visibility, Long lockVersion) {}
    public record AttachSource(Long dataSourceId, String schemaName, String applicationLabel) {}
    public record EdgeDecision(String status) {}

    public record TopologySummary(long id, String name, String domain, String description, String status,
                                  String currentHash, int currentVersion, long lockVersion,
                                  int sourceCount, int nodeCount, int edgeCount, String ownerUsername,
                                  String visibility, Instant createdAt, Instant updatedAt,
                                  Long ownerUserId, Long ownerGroupId) {}

    record TopologyRow(long id, String name, String domain, String description, String status,
                       Long currentOperationId, String currentHash, int currentVersion, long lockVersion,
                       Long ownerUserId, String ownerUsername, Long ownerGroupId, String visibility,
                       Instant createdAt, Instant updatedAt) {}

    public record SourceBinding(long id, long topologyId, Long dataSourceId, String dataSourceName,
                                String engine, String schemaName, String applicationLabel,
                                String providerMode, Instant capturedAt, int nodeCount, int edgeCount) {}

    public record DiscoveryOperation(long id, long topologyId, String status, int percent,
                                     int completedSources, int totalSources, int completedObjects,
                                     int totalObjects, String currentSource, String currentSchema,
                                     String currentObject, String message, String errorMessage,
                                     boolean cancelRequested, String requestedBy, Instant startedAt,
                                     Instant finishedAt, Instant createdAt) {}

    public record GraphSnapshot(List<GraphNode> nodes, List<GraphEdge> edges,
                                int totalNodes, int totalEdges, boolean truncated) {}
    public record GraphNode(long id, long sourceBindingId, String application, String schema,
                            String name, String objectType, int columnCount, int primaryKeyCount,
                            Long rowEstimate) {}
    public record GraphEdge(long id, String constraintName, long childNodeId, long parentNodeId,
                            List<String> childColumns, List<String> parentColumns, String evidenceType,
                            String decisionStatus, int confidence, boolean enabled, String evidenceJson) {}
    public record ColumnSnapshot(long id, int ordinal, String name, String dataType, int jdbcType,
                                 Long length, Integer scale, boolean nullable, boolean primaryKey,
                                 boolean uniqueKey, boolean generated, String defaultExpression) {}
    public record TopologyVersion(long id, int versionNumber, String contentHash, int nodeCount,
                                  int edgeCount, String summaryJson, String createdBy, Instant createdAt) {}

    static DiscoveryOperation operation(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new DiscoveryOperation(rs.getLong("id"), rs.getLong("topology_id"), rs.getString("status"),
                rs.getInt("percent"), rs.getInt("completed_sources"), rs.getInt("total_sources"),
                rs.getInt("completed_objects"), rs.getInt("total_objects"),
                rs.getString("current_source"), rs.getString("active_schema"),
                rs.getString("current_object"), rs.getString("message"),
                rs.getString("error_message"), rs.getBoolean("cancel_requested"),
                rs.getString("requested_by"), instant(rs.getTimestamp("started_at")),
                instant(rs.getTimestamp("finished_at")), instant(rs.getTimestamp("created_at")));
    }
}
