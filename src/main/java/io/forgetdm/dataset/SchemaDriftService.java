package io.forgetdm.dataset;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import io.forgetdm.platform.ClusterLeaseService;
import io.forgetdm.security.AccessContext;
import io.forgetdm.security.OwnershipGuard;
import jakarta.transaction.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Enterprise schema-drift management for DataScope.
 *
 * <p>A baseline is an explicitly accepted, immutable snapshot. Checks compare live source and target
 * metadata with that baseline and retain the evidence. GET endpoints never touch an external database;
 * live metadata is read only by an explicit scan, a pre-provision gate, or the HA-safe scheduler.</p>
 */
@Service
public class SchemaDriftService {

    private static final Set<String> BLOCKING_SEVERITIES = Set.of("BLOCKER", "CRITICAL", "HIGH");

    private final DataSetDefinitionRepository definitions;
    private final TableProfileRepository profiles;
    private final ColumnOverrideRepository overrides;
    private final UserDefinedPkRepository customPks;
    private final UserDefinedRelationshipRepository relationships;
    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final OwnershipGuard ownership;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ClusterLeaseService leases;

    public SchemaDriftService(DataSetDefinitionRepository definitions,
                              TableProfileRepository profiles,
                              ColumnOverrideRepository overrides,
                              UserDefinedPkRepository customPks,
                              UserDefinedRelationshipRepository relationships,
                              DataSourceService dataSources,
                              ConnectionFactory connections,
                              OwnershipGuard ownership,
                              AuditService audit,
                              JdbcTemplate jdbc,
                              ObjectMapper json,
                              ClusterLeaseService leases) {
        this.definitions = definitions;
        this.profiles = profiles;
        this.overrides = overrides;
        this.customPks = customPks;
        this.relationships = relationships;
        this.dataSources = dataSources;
        this.connections = connections;
        this.ownership = ownership;
        this.audit = audit;
        this.jdbc = jdbc;
        this.json = json;
        this.leases = leases;
    }

    public record BaselineRequest(String reason) {}
    public record AcceptRequest(Long runId, String reason) {}
    public record ScheduleRequest(Boolean enabled, String cron, String zone) {}
    public record MonitorRequest(String name, String description, Long dataSourceId, String schemaName) {}

    public record SchemaSnapshot(Instant capturedAt, List<TableSnapshot> tables) {}
    public record TableSnapshot(String scope, Long dataSourceId, String dataSourceName, String schema,
                                String logicalTable, String physicalTable, boolean reachable, boolean exists,
                                String engine, String constraintCoverage, String error,
                                List<ColumnSnapshot> columns, List<String> primaryKey,
                                List<ForeignKeySnapshot> foreignKeys, List<IndexSnapshot> indexes,
                                List<CheckSnapshot> checks) {}
    public record ColumnSnapshot(String name, int ordinal, String typeName, int jdbcType, Long length,
                                 Integer scale, boolean nullable, boolean generated, String defaultExpression) {}
    public record ForeignKeySnapshot(String name, List<String> childColumns, String parentSchema,
                                     String parentTable, List<String> parentColumns,
                                     short updateRule, short deleteRule) {}
    public record IndexSnapshot(String name, boolean unique, List<String> columns) {}
    public record CheckSnapshot(String name, String expression) {}
    public record DriftIssue(String type, String severity, String scope, Long dataSourceId, String schema,
                             String table, String column, String artifact, String detail,
                             String beforeValue, String afterValue, List<String> affectedJobs) {}

    private record TableSpec(String scope, Long dataSourceId, String schema, String logicalTable,
                             String physicalTable) {}
    private record ContextKey(String scope, Long dataSourceId, String schema) {}
    private record BaselineRow(long id, int version, String fingerprint, String snapshotJson,
                               String reason, String acceptedBy, Instant acceptedAt) {}
    private record RunRow(long id, Long baselineId, String triggerType, String status, String fingerprint,
                          int issueCount, int blockingCount, String summaryJson, String issuesJson,
                          String snapshotJson, String checkedBy, Instant checkedAt) {}

    /** Whole-schema monitors are retained in the same evidence model but never shown as DataScope blueprints. */
    public List<Map<String, Object>> listMonitors() {
        return definitions.findByDriftMonitorOnlyTrue().stream()
                .filter(definition -> ownership.canSee(definition.getOwnerUserId(), definition.getOwnerGroupId(),
                        definition.getVisibility()))
                .sorted(Comparator.comparing(DataSetDefinitionEntity::getUpdatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::monitorSummary)
                .toList();
    }

    @Transactional
    public Map<String, Object> createMonitor(MonitorRequest request) {
        if (request == null) throw ApiException.bad("Monitor details are required");
        String name = clean(request.name());
        if (name == null || name.length() < 8 || name.length() > 64) {
            throw ApiException.bad("Monitor name must be between 8 and 64 characters");
        }
        if (request.dataSourceId() == null) throw ApiException.bad("Choose a data source");
        String schema = clean(request.schemaName());
        if (schema == null || schema.length() > 128) throw ApiException.bad("Choose a valid schema");
        DataSourceEntity source = dataSources.getSourceCapable(request.dataSourceId());
        // This validates the physical schema now, so a typo never becomes a scheduled runtime failure.
        dataSources.tables(source.getId(), schema);
        definitions.findByName(name).ifPresent(existing -> {
            throw ApiException.bad("Name '" + name + "' is already in use");
        });
        definitions.findByDriftMonitorOnlyTrue().stream()
                .filter(existing -> ownership.canSee(existing.getOwnerUserId(), existing.getOwnerGroupId(),
                        existing.getVisibility()))
                .filter(existing -> Objects.equals(existing.getDataSourceId(), source.getId())
                        && schema.equalsIgnoreCase(String.valueOf(existing.getSchemaName())))
                .findFirst()
                .ifPresent(existing -> {
                    throw ApiException.conflict("Schema " + source.getName() + "." + schema
                            + " is already monitored by '" + existing.getName() + "'");
                });

        DataSetDefinitionEntity monitor = new DataSetDefinitionEntity();
        monitor.setName(name);
        monitor.setDescription(trimTo(request.description(), 500));
        monitor.setDataSourceId(source.getId());
        monitor.setSchemaName(schema);
        monitor.setScopeKind("RELATIONAL");
        monitor.setDriftMonitorOnly(true);
        monitor.setOwnerUserId(ownership.defaultOwnerUserId());
        monitor.setOwnerUsername(ownership.defaultOwnerUsername());
        monitor.setOwnerGroupId(ownership.defaultOwnerGroupId());
        monitor.setVisibility(ownership.defaultVisibility());
        monitor.setUpdatedAt(Instant.now());
        DataSetDefinitionEntity saved = definitions.save(monitor);
        audit.record(actor(), "SCHEMA_DRIFT_MONITOR_CREATED", "SCHEMA_DRIFT", "schema-monitor",
                String.valueOf(saved.getId()), saved.getName(), "SUCCESS",
                "Created whole-schema drift monitor",
                write(Map.of("dataSourceId", source.getId(), "schema", schema)));
        return monitorSummary(saved);
    }

    @Transactional
    public void deleteMonitor(long monitorId) {
        DataSetDefinitionEntity monitor = monitorDefinition(monitorId);
        definitions.delete(monitor);
        audit.record(actor(), "SCHEMA_DRIFT_MONITOR_DELETED", "SCHEMA_DRIFT", "schema-monitor",
                String.valueOf(monitorId), monitor.getName(), "SUCCESS",
                "Deleted schema drift monitor and its associated evidence", null);
    }

    public void requireMonitor(long monitorId) {
        monitorDefinition(monitorId);
    }

    /** Retained state only: opening DataScope never launches an external metadata scan. */
    public Map<String, Object> current(long datasetId) {
        DataSetDefinitionEntity definition = definition(datasetId);
        BaselineRow baseline = activeBaseline(datasetId);
        RunRow run = latestRun(datasetId);
        return report(definition, baseline, run);
    }

    /** Capture and explicitly accept the live state as a new immutable baseline version. */
    @Transactional
    public Map<String, Object> createBaseline(long datasetId, BaselineRequest request) {
        DataSetDefinitionEntity definition = definition(datasetId);
        String reason = requireReason(request == null ? null : request.reason());
        SchemaSnapshot snapshot = capture(definition, null);
        assertBaselineCaptureIsUsable(snapshot);
        String snapshotJson = write(snapshot);
        String fingerprint = fingerprint(snapshot);
        String actor = actor();

        jdbc.queryForObject("SELECT id FROM dataset_definitions WHERE id = ? FOR UPDATE", Long.class, datasetId);
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 FROM schema_drift_baselines WHERE dataset_id = ?",
                Integer.class, datasetId);
        jdbc.update("UPDATE schema_drift_baselines SET active = FALSE WHERE dataset_id = ? AND active = TRUE", datasetId);
        jdbc.update("INSERT INTO schema_drift_baselines(dataset_id, version_no, fingerprint, snapshot_json, " +
                        "acceptance_reason, accepted_by, accepted_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)",
                datasetId, version, fingerprint, snapshotJson, reason, actor, Timestamp.from(Instant.now()));

        BaselineRow baseline = activeBaseline(datasetId);
        audit.record(actor, "SCHEMA_DRIFT_BASELINE_ACCEPTED", "DATASCOPE", "dataset",
                String.valueOf(datasetId), definition.getName(), "SUCCESS",
                "Accepted schema baseline version " + version,
                write(Map.of("version", version, "fingerprint", fingerprint, "reason", reason,
                        "tables", snapshot.tables().size())));
        return report(definition, baseline, latestRun(datasetId));
    }

    /** Compare live source and target structures with the active baseline and retain the evidence. */
    public Map<String, Object> check(long datasetId, String triggerType) {
        DataSetDefinitionEntity definition = definition(datasetId);
        BaselineRow baseline = activeBaseline(datasetId);
        if (baseline == null) return report(definition, null, latestRun(datasetId));

        SchemaSnapshot expected = read(baseline.snapshotJson(), SchemaSnapshot.class);
        SchemaSnapshot actual = capture(definition, expected);
        List<DriftIssue> issues = compare(datasetId, definition, expected, actual);
        int blocking = (int) issues.stream().filter(issue -> BLOCKING_SEVERITIES.contains(issue.severity())).count();
        String status = blocking > 0 ? "BLOCKED" : issues.isEmpty() ? "READY" : "WARN";
        String snapshotJson = write(actual);
        String fingerprint = fingerprint(actual);
        Map<String, Object> summary = summary(issues, expected, actual);
        String actor = "SCHEDULED".equalsIgnoreCase(triggerType) ? "system" : actor();
        Instant now = Instant.now();

        jdbc.update("INSERT INTO schema_drift_runs(dataset_id, baseline_id, trigger_type, status, fingerprint, " +
                        "issue_count, blocking_count, summary_json, issues_json, snapshot_json, checked_by, checked_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                datasetId, baseline.id(), normalizeTrigger(triggerType), status, fingerprint,
                issues.size(), blocking, write(summary), write(issues), snapshotJson, actor, Timestamp.from(now));
        jdbc.update("UPDATE schema_drift_schedules SET last_run_at = ? WHERE dataset_id = ?",
                Timestamp.from(now), datasetId);

        audit.record(actor, "SCHEMA_DRIFT_CHECKED", "DATASCOPE", "dataset",
                String.valueOf(datasetId), definition.getName(), blocking > 0 ? "FAILURE" : "SUCCESS",
                "Schema drift check completed with " + issues.size() + " issue(s), " + blocking + " blocking",
                write(Map.of("baselineVersion", baseline.version(), "status", status,
                        "issueCount", issues.size(), "blockingCount", blocking, "trigger", normalizeTrigger(triggerType))));
        return report(definition, baseline, latestRun(datasetId));
    }

    /** Accept one retained scan exactly as captured; never silently re-scan during acceptance. */
    @Transactional
    public Map<String, Object> acceptRun(long datasetId, AcceptRequest request) {
        DataSetDefinitionEntity definition = definition(datasetId);
        if (request == null || request.runId() == null) throw ApiException.bad("Choose a drift scan to accept");
        String reason = requireReason(request.reason());
        RunRow run = run(datasetId, request.runId());
        String actor = actor();

        jdbc.queryForObject("SELECT id FROM dataset_definitions WHERE id = ? FOR UPDATE", Long.class, datasetId);
        Integer version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_no), 0) + 1 FROM schema_drift_baselines WHERE dataset_id = ?",
                Integer.class, datasetId);
        jdbc.update("UPDATE schema_drift_baselines SET active = FALSE WHERE dataset_id = ? AND active = TRUE", datasetId);
        jdbc.update("INSERT INTO schema_drift_baselines(dataset_id, version_no, fingerprint, snapshot_json, " +
                        "acceptance_reason, accepted_by, accepted_at, active) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE)",
                datasetId, version, run.fingerprint(), run.snapshotJson(), reason, actor, Timestamp.from(Instant.now()));

        audit.record(actor, "SCHEMA_DRIFT_RUN_ACCEPTED", "DATASCOPE", "dataset",
                String.valueOf(datasetId), definition.getName(), "SUCCESS",
                "Accepted drift scan " + run.id() + " as baseline version " + version,
                write(Map.of("runId", run.id(), "version", version, "reason", reason,
                        "previousStatus", run.status(), "previousIssueCount", run.issueCount())));
        return report(definition, activeBaseline(datasetId), latestRun(datasetId));
    }

    public List<Map<String, Object>> history(long datasetId, int requestedLimit) {
        definition(datasetId);
        int limit = Math.max(1, Math.min(requestedLimit, 100));
        return jdbc.query("SELECT id, baseline_id, trigger_type, status, fingerprint, issue_count, blocking_count, " +
                        "summary_json, checked_by, checked_at FROM schema_drift_runs WHERE dataset_id = ? " +
                        "ORDER BY checked_at DESC FETCH FIRST " + limit + " ROWS ONLY",
                (rs, row) -> {
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("id", rs.getLong("id"));
                    out.put("baselineId", nullableLong(rs, "baseline_id"));
                    out.put("triggerType", rs.getString("trigger_type"));
                    out.put("status", rs.getString("status"));
                    out.put("fingerprint", rs.getString("fingerprint"));
                    out.put("issueCount", rs.getInt("issue_count"));
                    out.put("blockingCount", rs.getInt("blocking_count"));
                    out.put("summary", readMap(rs.getString("summary_json")));
                    out.put("checkedBy", rs.getString("checked_by"));
                    out.put("checkedAt", instant(rs, "checked_at"));
                    return out;
                }, datasetId);
    }

    public Map<String, Object> schedule(long datasetId) {
        definition(datasetId);
        List<Map<String, Object>> rows = jdbc.query(
                "SELECT enabled, cron_expression, zone_id, next_run_at, last_run_at, configured_by, updated_at " +
                        "FROM schema_drift_schedules WHERE dataset_id = ?",
                (rs, row) -> scheduleRow(rs), datasetId);
        return rows.isEmpty() ? Map.of("enabled", false, "zone", "UTC") : rows.get(0);
    }

    @Transactional
    public Map<String, Object> updateSchedule(long datasetId, ScheduleRequest request) {
        DataSetDefinitionEntity definition = definition(datasetId);
        boolean enabled = request != null && Boolean.TRUE.equals(request.enabled());
        String cron = clean(request == null ? null : request.cron());
        ZoneId zone = zone(request == null ? null : request.zone());
        Instant next = null;
        if (enabled) {
            if (cron == null) throw ApiException.bad("A cron expression is required to enable drift monitoring");
            next = nextRun(cron, zone, Instant.now());
        }
        String actor = actor();
        Timestamp now = Timestamp.from(Instant.now());
        int changed = jdbc.update("UPDATE schema_drift_schedules SET enabled = ?, cron_expression = ?, zone_id = ?, " +
                        "next_run_at = ?, configured_by = ?, updated_at = ? WHERE dataset_id = ?",
                enabled, cron, zone.getId(), ts(next), actor, now, datasetId);
        if (changed == 0) {
            jdbc.update("INSERT INTO schema_drift_schedules(dataset_id, enabled, cron_expression, zone_id, next_run_at, " +
                            "configured_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    datasetId, enabled, cron, zone.getId(), ts(next), actor, now);
        }
        audit.record(actor, "SCHEMA_DRIFT_SCHEDULE_UPDATED", "DATASCOPE", "dataset",
                String.valueOf(datasetId), definition.getName(), "SUCCESS",
                enabled ? "Enabled scheduled schema drift checks" : "Disabled scheduled schema drift checks",
                write(Map.of("enabled", enabled, "cron", cron == null ? "" : cron, "zone", zone.getId())));
        return schedule(datasetId);
    }

    /** Hard pre-provision gate. An accepted baseline is required and blocking drift cannot be ignored silently. */
    public void assertProvisionAllowed(long datasetId, String specJson) {
        Map<String, Object> checked = check(datasetId, "PRE_PROVISION");
        boolean baselineRequired = Boolean.TRUE.equals(checked.get("baselineRequired"));
        boolean blocking = Boolean.TRUE.equals(checked.get("blocking"));
        if (!baselineRequired && !blocking) return;

        String overrideReason = driftOverrideReason(specJson);
        boolean canOverride = AccessContext.current()
                .map(p -> p.hasPermission("admin.all") || p.hasPermission("provision.approve"))
                .orElse(false);
        if (overrideReason != null && canOverride) {
            audit.record(actor(), "SCHEMA_DRIFT_PROVISION_OVERRIDE", "DATASCOPE", "dataset",
                    String.valueOf(datasetId), null, "SUCCESS", overrideReason,
                    write(Map.of("baselineRequired", baselineRequired, "blocking", blocking)));
            return;
        }
        String reason = baselineRequired
                ? "Capture and accept a schema baseline before provisioning this DataScope."
                : "Blocking schema drift was detected. Review it and accept a new baseline, or have an authorized " +
                  "reviewer provide a drift override reason.";
        throw ApiException.conflict(reason);
    }

    @Scheduled(fixedDelayString = "${forgetdm.schema-drift.scheduler-ms:60000}")
    public void runDueSchedules() {
        if (!leases.acquire("schema-drift-scheduler", Duration.ofSeconds(55))) return;
        Instant now = Instant.now();
        List<Map<String, Object>> due = jdbc.query(
                "SELECT dataset_id, cron_expression, zone_id FROM schema_drift_schedules " +
                        "WHERE enabled = TRUE AND next_run_at IS NOT NULL AND next_run_at <= ?",
                (rs, row) -> Map.of("datasetId", rs.getLong("dataset_id"),
                        "cron", rs.getString("cron_expression"), "zone", rs.getString("zone_id")),
                Timestamp.from(now));
        for (Map<String, Object> item : due) {
            long datasetId = ((Number) item.get("datasetId")).longValue();
            String cron = String.valueOf(item.get("cron"));
            ZoneId zone = zone(String.valueOf(item.get("zone")));
            Instant next;
            try { next = nextRun(cron, zone, now); }
            catch (Exception e) {
                jdbc.update("UPDATE schema_drift_schedules SET enabled = FALSE, updated_at = ? WHERE dataset_id = ?",
                        Timestamp.from(now), datasetId);
                continue;
            }
            int claimed = jdbc.update("UPDATE schema_drift_schedules SET next_run_at = ?, updated_at = ? " +
                            "WHERE dataset_id = ? AND enabled = TRUE AND next_run_at <= ?",
                    Timestamp.from(next), Timestamp.from(now), datasetId, Timestamp.from(now));
            if (claimed == 0) continue;
            try { check(datasetId, "SCHEDULED"); }
            catch (Exception e) {
                audit.record("system", "SCHEMA_DRIFT_CHECK_FAILED", "DATASCOPE", "dataset",
                        String.valueOf(datasetId), null, "FAILURE", rootMessage(e), null);
            }
        }
    }

    // ---------------------------------------------------------------- capture

    private SchemaSnapshot capture(DataSetDefinitionEntity definition, SchemaSnapshot expected) {
        if (definition.isDriftMonitorOnly()) return captureSchemaMonitor(definition, expected);
        List<TableSpec> specs = tableSpecs(definition);
        Map<ContextKey, List<TableSpec>> grouped = new LinkedHashMap<>();
        for (TableSpec spec : specs) {
            String schema = clean(spec.schema());
            grouped.computeIfAbsent(new ContextKey(spec.scope(), spec.dataSourceId(), schema), ignored -> new ArrayList<>())
                    .add(spec);
        }

        List<TableSnapshot> tables = new ArrayList<>();
        for (Map.Entry<ContextKey, List<TableSpec>> entry : grouped.entrySet()) {
            ContextKey context = entry.getKey();
            DataSourceEntity source;
            try {
                source = "TARGET".equals(context.scope())
                        ? dataSources.getTargetCapable(context.dataSourceId())
                        : dataSources.getSourceCapable(context.dataSourceId());
            } catch (Exception e) {
                for (TableSpec spec : entry.getValue()) tables.add(unreachable(spec, null, rootMessage(e)));
                continue;
            }
            try (Connection connection = connections.openPooled(source)) {
                String schema = DataSourceService.normalizeSchema(connection, context.schema());
                Map<String, String> actualTables = actualTables(connection, source, schema);
                for (TableSpec spec : entry.getValue()) {
                    String actual = actualTables.get(spec.physicalTable().toLowerCase(Locale.ROOT));
                    tables.add(actual == null
                            ? missing(spec, source, schema)
                            : readTable(connection, source, schema, spec, actual));
                }
            } catch (Exception e) {
                for (TableSpec spec : entry.getValue()) tables.add(unreachable(spec, source, rootMessage(e)));
            }
        }
        tables.sort(Comparator.comparing(TableSnapshot::scope)
                .thenComparing(t -> String.valueOf(t.dataSourceId()))
                .thenComparing(t -> String.valueOf(t.schema()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(TableSnapshot::physicalTable, String.CASE_INSENSITIVE_ORDER));
        return new SchemaSnapshot(Instant.now(), List.copyOf(tables));
    }

    private SchemaSnapshot captureSchemaMonitor(DataSetDefinitionEntity definition, SchemaSnapshot expected) {
        DataSourceEntity source = null;
        try {
            source = dataSources.getSourceCapable(definition.getDataSourceId());
            try (Connection connection = connections.openPooled(source)) {
                String schema = DataSourceService.normalizeSchema(connection, definition.getSchemaName());
                Map<String, String> actual = actualTables(connection, source, schema);
                List<String> names = actual.values().stream()
                        .sorted(String.CASE_INSENSITIVE_ORDER)
                        .toList();
                List<TableSnapshot> tables = new ArrayList<>();
                for (String table : names) {
                    TableSpec spec = new TableSpec("SOURCE", source.getId(), schema, table, table);
                    tables.add(readTable(connection, source, schema, spec, table));
                }
                return new SchemaSnapshot(Instant.now(), List.copyOf(tables));
            }
        } catch (Exception error) {
            List<TableSpec> fallback = expected == null ? List.of() : expected.tables().stream()
                    .filter(table -> "SOURCE".equals(table.scope()))
                    .map(table -> new TableSpec("SOURCE", definition.getDataSourceId(), definition.getSchemaName(),
                            table.logicalTable(), table.physicalTable()))
                    .toList();
            if (fallback.isEmpty()) {
                fallback = List.of(new TableSpec("SOURCE", definition.getDataSourceId(),
                        definition.getSchemaName(), "__SCHEMA__", "__SCHEMA__"));
            }
            DataSourceEntity resolved = source;
            return new SchemaSnapshot(Instant.now(), fallback.stream()
                    .map(spec -> unreachable(spec, resolved, rootMessage(error)))
                    .toList());
        }
    }

    private static void assertBaselineCaptureIsUsable(SchemaSnapshot snapshot) {
        List<String> invalidSources = snapshot.tables().stream()
                .filter(table -> "SOURCE".equals(table.scope()) && (!table.reachable() || !table.exists()))
                .map(table -> table.dataSourceName() + "." + table.schema() + "." + table.physicalTable()
                        + (table.error() == null ? "" : " (" + table.error() + ")"))
                .limit(10)
                .toList();
        if (!invalidSources.isEmpty()) {
            throw ApiException.conflict("Cannot accept a schema baseline while source objects are unavailable: "
                    + String.join(", ", invalidSources));
        }
    }

    private List<TableSpec> tableSpecs(DataSetDefinitionEntity definition) {
        List<TableProfileEntity> included = profiles.findByDatasetId(definition.getId()).stream()
                .filter(TableProfileEntity::isIncluded)
                .sorted(Comparator.comparing(TableProfileEntity::getTableName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<TableSpec> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (TableProfileEntity profile : included) {
            Long sourceId = profile.getSourceDataSourceId() != null
                    ? profile.getSourceDataSourceId() : definition.getDataSourceId();
            String sourceSchema = clean(profile.getSourceSchemaName()) != null
                    ? profile.getSourceSchemaName() : definition.getSchemaName();
            addSpec(out, seen, new TableSpec("SOURCE", sourceId, sourceSchema,
                    profile.getTableName(), profile.getTableName()));
            if (definition.getTargetDataSourceId() != null) {
                String targetTable = clean(profile.getTargetTableName()) != null
                        ? profile.getTargetTableName() : profile.getTableName();
                addSpec(out, seen, new TableSpec("TARGET", definition.getTargetDataSourceId(),
                        definition.getTargetSchemaName(), profile.getTableName(), targetTable));
            }
        }
        if (out.isEmpty() && clean(definition.getDriverTable()) != null) {
            addSpec(out, seen, new TableSpec("SOURCE", definition.getDataSourceId(), definition.getSchemaName(),
                    definition.getDriverTable(), definition.getDriverTable()));
        }
        return out;
    }

    private static void addSpec(List<TableSpec> out, Set<String> seen, TableSpec spec) {
        String key = spec.scope() + "|" + spec.dataSourceId() + "|" + lower(spec.schema()) + "|" + lower(spec.physicalTable());
        if (seen.add(key)) out.add(spec);
    }

    private Map<String, String> actualTables(Connection connection, DataSourceEntity source, String schema) throws SQLException {
        SqlDialect dialect = SqlDialect.of(source);
        String catalog = dialect == SqlDialect.MYSQL ? (schema == null ? connection.getCatalog() : schema) : null;
        String schemaPattern = dialect == SqlDialect.MYSQL ? null : schema;
        Map<String, String> tables = new HashMap<>();
        try (ResultSet rs = connection.getMetaData().getTables(
                catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
            while (rs.next()) {
                String name = rs.getString("TABLE_NAME");
                if (name != null && !SqlDialect.isSystemTable(name)) tables.put(name.toLowerCase(Locale.ROOT), name);
            }
        }
        return tables;
    }

    private TableSnapshot readTable(Connection connection, DataSourceEntity source, String schema,
                                    TableSpec spec, String actualTable) throws SQLException {
        SqlDialect dialect = SqlDialect.of(source);
        String catalog = dialect == SqlDialect.MYSQL ? (schema == null ? connection.getCatalog() : schema) : null;
        String schemaPattern = dialect == SqlDialect.MYSQL ? null : schema;
        DatabaseMetaData metadata = connection.getMetaData();
        List<ColumnSnapshot> columns = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(catalog, schemaPattern, actualTable, "%")) {
            while (rs.next()) {
                columns.add(new ColumnSnapshot(rs.getString("COLUMN_NAME"), rs.getInt("ORDINAL_POSITION"),
                        rs.getString("TYPE_NAME"), rs.getInt("DATA_TYPE"), nullableLong(rs, "COLUMN_SIZE"),
                        nullableInteger(rs, "DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        "YES".equalsIgnoreCase(safeString(rs, "IS_GENERATEDCOLUMN"))
                                || "YES".equalsIgnoreCase(safeString(rs, "IS_AUTOINCREMENT")),
                        safeString(rs, "COLUMN_DEF")));
            }
        }
        columns.sort(Comparator.comparingInt(ColumnSnapshot::ordinal));

        TreeMap<Short, String> primary = new TreeMap<>();
        try (ResultSet rs = metadata.getPrimaryKeys(catalog, schemaPattern, actualTable)) {
            while (rs.next()) primary.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }

        Map<String, MutableForeignKey> foreignKeys = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getImportedKeys(catalog, schemaPattern, actualTable)) {
            while (rs.next()) {
                String name = firstText(rs.getString("FK_NAME"), "FK_" + actualTable);
                String parent = rs.getString("PKTABLE_NAME");
                String key = lower(name) + "|" + lower(parent);
                String parentSchema = rsSafe(rs, "PKTABLE_SCHEM");
                short updateRule = rs.getShort("UPDATE_RULE");
                short deleteRule = rs.getShort("DELETE_RULE");
                MutableForeignKey fk = foreignKeys.computeIfAbsent(key,
                        ignored -> new MutableForeignKey(name, parentSchema, parent, updateRule, deleteRule));
                fk.child.put(rs.getShort("KEY_SEQ"), rs.getString("FKCOLUMN_NAME"));
                fk.parent.put(rs.getShort("KEY_SEQ"), rs.getString("PKCOLUMN_NAME"));
            }
        }

        Map<String, MutableIndex> indexes = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getIndexInfo(catalog, schemaPattern, actualTable, false, false)) {
            while (rs.next()) {
                String name = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (name == null || column == null || rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                boolean unique = !rs.getBoolean("NON_UNIQUE");
                MutableIndex index = indexes.computeIfAbsent(lower(name),
                        ignored -> new MutableIndex(name, unique));
                index.columns.put(rs.getShort("ORDINAL_POSITION"), column);
            }
        }

        List<CheckSnapshot> checks = readChecks(connection, dialect, schema, actualTable);
        String coverage = checks == null ? "PK_UK_FK_INDEX" : "PK_UK_FK_INDEX_CHECK";
        return new TableSnapshot(spec.scope(), source.getId(), source.getName(), schema,
                spec.logicalTable(), actualTable, true, true, dialect.name(), coverage, null,
                List.copyOf(columns), List.copyOf(primary.values()),
                foreignKeys.values().stream().map(MutableForeignKey::freeze)
                        .sorted(Comparator.comparing(ForeignKeySnapshot::name, String.CASE_INSENSITIVE_ORDER)).toList(),
                indexes.values().stream().map(MutableIndex::freeze)
                        .sorted(Comparator.comparing(IndexSnapshot::name, String.CASE_INSENSITIVE_ORDER)).toList(),
                checks == null ? List.of() : checks);
    }

    /** Null means the connector cannot expose CHECK text; empty means supported and no checks exist. */
    private List<CheckSnapshot> readChecks(Connection connection, SqlDialect dialect, String schema, String table) {
        String sql = switch (dialect) {
            case POSTGRES -> "SELECT con.conname, pg_get_constraintdef(con.oid) FROM pg_constraint con " +
                    "JOIN pg_class rel ON rel.oid = con.conrelid JOIN pg_namespace n ON n.oid = rel.relnamespace " +
                    "WHERE con.contype = 'c' AND n.nspname = ? AND rel.relname = ?";
            case ORACLE -> "SELECT constraint_name, search_condition_vc FROM all_constraints " +
                    "WHERE owner = UPPER(?) AND table_name = UPPER(?) AND constraint_type = 'C'";
            case SQLSERVER -> "SELECT cc.name, cc.definition FROM sys.check_constraints cc " +
                    "JOIN sys.tables t ON t.object_id = cc.parent_object_id JOIN sys.schemas s ON s.schema_id = t.schema_id " +
                    "WHERE s.name = ? AND t.name = ?";
            case DB2 -> "SELECT constname, text FROM syscat.checks WHERE tabschema = UPPER(?) AND tabname = UPPER(?)";
            case MYSQL -> "SELECT tc.constraint_name, cc.check_clause FROM information_schema.table_constraints tc " +
                    "JOIN information_schema.check_constraints cc ON cc.constraint_schema = tc.constraint_schema " +
                    "AND cc.constraint_name = tc.constraint_name WHERE tc.table_schema = ? AND tc.table_name = ? " +
                    "AND tc.constraint_type = 'CHECK'";
            default -> null;
        };
        if (sql == null) return null;
        List<CheckSnapshot> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            ps.setString(2, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new CheckSnapshot(rs.getString(1), normalizeExpression(rs.getString(2))));
            }
            out.sort(Comparator.comparing(CheckSnapshot::name, String.CASE_INSENSITIVE_ORDER));
            return List.copyOf(out);
        } catch (SQLException unsupported) {
            return null;
        }
    }

    private TableSnapshot unreachable(TableSpec spec, DataSourceEntity source, String error) {
        return new TableSnapshot(spec.scope(), spec.dataSourceId(), source == null ? null : source.getName(),
                spec.schema(), spec.logicalTable(), spec.physicalTable(), false, false,
                source == null ? null : SqlDialect.of(source).name(), "NONE", error,
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private TableSnapshot missing(TableSpec spec, DataSourceEntity source, String schema) {
        return new TableSnapshot(spec.scope(), source.getId(), source.getName(), schema,
                spec.logicalTable(), spec.physicalTable(), true, false, SqlDialect.of(source).name(),
                "NONE", "Table not found", List.of(), List.of(), List.of(), List.of(), List.of());
    }

    // --------------------------------------------------------------- compare

    List<DriftIssue> compare(long datasetId, DataSetDefinitionEntity definition,
                             SchemaSnapshot expected, SchemaSnapshot actual) {
        Map<String, TableSnapshot> before = index(expected.tables());
        Map<String, TableSnapshot> after = index(actual.tables());
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<String> affectedJobs = affectedJobs(datasetId);
        List<DriftIssue> issues = new ArrayList<>();

        for (String key : keys) {
            TableSnapshot oldTable = before.get(key);
            TableSnapshot newTable = after.get(key);
            TableSnapshot table = newTable != null ? newTable : oldTable;
            if (newTable == null) {
                issues.add(issue(definition.isDriftMonitorOnly() ? "TABLE_REMOVED" : "TABLE_REMOVED_FROM_SCOPE",
                        "BLOCKER", table, null, definition,
                        definition.isDriftMonitorOnly()
                                ? "A baseline table no longer exists in the monitored schema"
                                : "A baseline table is no longer part of this DataScope capture",
                        table.physicalTable(), null, affectedJobs));
                continue;
            }
            if (!newTable.reachable()) {
                issues.add(issue("SOURCE_UNREACHABLE", "BLOCKER", newTable, null, definition,
                        firstText(newTable.error(), "Metadata source is unreachable"), null, null, affectedJobs));
                continue;
            }
            if (oldTable == null) {
                issues.add(issue(definition.isDriftMonitorOnly() ? "TABLE_ADDED" : "TABLE_ADDED_TO_SCOPE",
                        "INFO", newTable, null, definition,
                        definition.isDriftMonitorOnly()
                                ? "A new table appeared in the monitored schema"
                                : "A table was added to the governed DataScope",
                        null, newTable.physicalTable(), affectedJobs));
                continue;
            }
            if (oldTable.exists() && !newTable.exists()) {
                issues.add(issue("TABLE_MISSING", "BLOCKER", newTable, null, definition,
                        "A baseline table no longer exists", oldTable.physicalTable(), null, affectedJobs));
                continue;
            }
            if (!oldTable.exists() && newTable.exists()) {
                issues.add(issue("TABLE_CREATED", "INFO", newTable, null, definition,
                        "A previously missing table now exists", null, newTable.physicalTable(), affectedJobs));
            }
            if (!newTable.exists()) continue;

            compareColumns(definition, oldTable, newTable, affectedJobs, issues);
            if (!sameNames(oldTable.primaryKey(), newTable.primaryKey())) {
                issues.add(issue("PRIMARY_KEY_CHANGED", "BLOCKER", newTable, null, definition,
                        "Primary-key columns changed", oldTable.primaryKey().toString(), newTable.primaryKey().toString(), affectedJobs));
            }
            if (!canonical(oldTable.foreignKeys()).equals(canonical(newTable.foreignKeys()))) {
                issues.add(issue("FOREIGN_KEY_CHANGED", "HIGH", newTable, null, definition,
                        "Foreign-key relationships changed", canonical(oldTable.foreignKeys()), canonical(newTable.foreignKeys()), affectedJobs));
            }
            if (!canonical(oldTable.indexes()).equals(canonical(newTable.indexes()))) {
                boolean uniqueChanged = !canonical(uniqueIndexes(oldTable.indexes())).equals(canonical(uniqueIndexes(newTable.indexes())));
                issues.add(issue(uniqueChanged ? "UNIQUE_CONSTRAINT_CHANGED" : "INDEX_CHANGED",
                        uniqueChanged ? "HIGH" : "LOW", newTable, null, definition,
                        uniqueChanged ? "Unique constraints or indexes changed" : "Non-unique indexes changed",
                        canonical(oldTable.indexes()), canonical(newTable.indexes()), affectedJobs));
            }
            if (!canonical(oldTable.checks()).equals(canonical(newTable.checks()))) {
                issues.add(issue("CHECK_CONSTRAINT_CHANGED", "HIGH", newTable, null, definition,
                        "CHECK constraint expressions changed", canonical(oldTable.checks()), canonical(newTable.checks()), affectedJobs));
            }
        }
        issues.sort(Comparator.comparingInt((DriftIssue issue) -> severityRank(issue.severity())).reversed()
                .thenComparing(DriftIssue::scope)
                .thenComparing(issue -> String.valueOf(issue.table()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(issue -> String.valueOf(issue.column()), String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(issues);
    }

    private void compareColumns(DataSetDefinitionEntity definition, TableSnapshot oldTable, TableSnapshot newTable,
                                List<String> affectedJobs, List<DriftIssue> issues) {
        Map<String, ColumnSnapshot> before = new LinkedHashMap<>();
        Map<String, ColumnSnapshot> after = new LinkedHashMap<>();
        oldTable.columns().forEach(column -> before.put(lower(column.name()), column));
        newTable.columns().forEach(column -> after.put(lower(column.name()), column));
        Set<String> names = new LinkedHashSet<>();
        names.addAll(before.keySet());
        names.addAll(after.keySet());
        for (String name : names) {
            ColumnSnapshot oldColumn = before.get(name);
            ColumnSnapshot newColumn = after.get(name);
            if (newColumn == null) {
                issues.add(issue("COLUMN_MISSING", "BLOCKER", newTable, oldColumn.name(), definition,
                        "A baseline column no longer exists", describe(oldColumn), null, affectedJobs));
                continue;
            }
            if (oldColumn == null) {
                issues.add(issue("COLUMN_ADDED", "INFO", newTable, newColumn.name(), definition,
                        "A new column was added", null, describe(newColumn), affectedJobs));
                continue;
            }
            if (!typeEquivalent(oldColumn, newColumn)) {
                issues.add(issue("DATA_TYPE_CHANGED", "HIGH", newTable, newColumn.name(), definition,
                        "The JDBC type or vendor type name changed", describe(oldColumn), describe(newColumn), affectedJobs));
            } else if (!Objects.equals(oldColumn.length(), newColumn.length())) {
                long oldLength = oldColumn.length() == null ? 0 : oldColumn.length();
                long newLength = newColumn.length() == null ? 0 : newColumn.length();
                String type = newLength < oldLength ? "LENGTH_NARROWED" : "LENGTH_WIDENED";
                String severity = newLength < oldLength ? "HIGH" : "INFO";
                issues.add(issue(type, severity, newTable, newColumn.name(), definition,
                        "Column length or precision changed", String.valueOf(oldColumn.length()),
                        String.valueOf(newColumn.length()), affectedJobs));
            }
            if (!Objects.equals(oldColumn.scale(), newColumn.scale())) {
                issues.add(issue("SCALE_CHANGED", "HIGH", newTable, newColumn.name(), definition,
                        "Numeric scale changed", String.valueOf(oldColumn.scale()), String.valueOf(newColumn.scale()), affectedJobs));
            }
            if (oldColumn.nullable() != newColumn.nullable()) {
                boolean tightened = oldColumn.nullable() && !newColumn.nullable();
                issues.add(issue(tightened ? "NULLABILITY_TIGHTENED" : "NULLABILITY_RELAXED",
                        tightened ? "HIGH" : "MEDIUM", newTable, newColumn.name(), definition,
                        "Column nullability changed", String.valueOf(oldColumn.nullable()),
                        String.valueOf(newColumn.nullable()), affectedJobs));
            }
            if (oldColumn.generated() != newColumn.generated()) {
                issues.add(issue("GENERATED_STATE_CHANGED", "HIGH", newTable, newColumn.name(), definition,
                        "Generated/identity behavior changed", String.valueOf(oldColumn.generated()),
                        String.valueOf(newColumn.generated()), affectedJobs));
            }
            if (!sameExpression(oldColumn.defaultExpression(), newColumn.defaultExpression())) {
                issues.add(issue("DEFAULT_CHANGED", "MEDIUM", newTable, newColumn.name(), definition,
                        "Default expression changed", oldColumn.defaultExpression(), newColumn.defaultExpression(), affectedJobs));
            }
        }
    }

    private DriftIssue issue(String type, String severity, TableSnapshot table, String column,
                             DataSetDefinitionEntity definition, String detail, String beforeValue,
                             String afterValue, List<String> affectedJobs) {
        return new DriftIssue(type, severity, table.scope(), table.dataSourceId(), table.schema(),
                table.physicalTable(), column, artifact(table, column, definition), detail,
                beforeValue, afterValue, affectedJobs);
    }

    private String artifact(TableSnapshot table, String column, DataSetDefinitionEntity definition) {
        if (definition.isDriftMonitorOnly()) return column == null ? "SCHEMA" : "SCHEMA_COLUMN";
        if ("TARGET".equals(table.scope())) return "PROVISION_TARGET";
        if (table.logicalTable().equalsIgnoreCase(String.valueOf(definition.getDriverTable()))) return "DRIVER_TABLE";
        if (column != null) {
            boolean mapped = overrides.findByDatasetIdAndTableName(definition.getId(), table.logicalTable()).stream()
                    .anyMatch(o -> column.equalsIgnoreCase(firstText(o.getSourceColumnName(), o.getColumnName())));
            if (mapped) return "COLUMN_MAP";
            boolean pk = customPks.findByDatasetId(definition.getId()).stream()
                    .anyMatch(p -> p.getTableName().equalsIgnoreCase(table.logicalTable())
                            && split(p.getColumnNames()).stream().anyMatch(column::equalsIgnoreCase));
            if (pk) return "CUSTOM_PRIMARY_KEY";
            boolean relation = relationships.findByDatasetId(definition.getId()).stream()
                    .anyMatch(r -> (r.getParentTable().equalsIgnoreCase(table.logicalTable())
                            && split(r.getParentColumns()).stream().anyMatch(column::equalsIgnoreCase))
                            || (r.getChildTable().equalsIgnoreCase(table.logicalTable())
                            && split(r.getChildColumns()).stream().anyMatch(column::equalsIgnoreCase)));
            if (relation) return "RELATIONSHIP";
        }
        return "TABLE_PROFILE";
    }

    // --------------------------------------------------------------- persistence/report

    private Map<String, Object> report(DataSetDefinitionEntity definition, BaselineRow baseline, RunRow run) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("datasetId", definition.getId());
        out.put("datasetName", definition.getName());
        out.put("monitorOnly", definition.isDriftMonitorOnly());
        out.put("dataSourceId", definition.getDataSourceId());
        out.put("schemaName", definition.getSchemaName());
        out.put("baselineRequired", baseline == null);
        out.put("baseline", baseline == null ? null : baselineMap(baseline));
        out.put("latestRunId", run == null ? null : run.id());
        out.put("status", baseline == null ? "BASELINE_REQUIRED" : run == null ? "NOT_CHECKED" : run.status());
        out.put("checkedAt", run == null ? null : run.checkedAt());
        out.put("checkedBy", run == null ? null : run.checkedBy());
        out.put("issues", run == null ? List.of() : readList(run.issuesJson()));
        out.put("summary", run == null ? emptySummary() : readMap(run.summaryJson()));
        out.put("blocking", run != null && run.blockingCount() > 0);
        out.put("blockingCount", run == null ? 0 : run.blockingCount());
        out.put("inSync", baseline != null && run != null && run.issueCount() == 0);
        out.put("sourceReachable", run == null || !containsIssue(run.issuesJson(), "SOURCE", "SOURCE_UNREACHABLE"));
        out.put("targetReachable", run == null || !containsIssue(run.issuesJson(), "TARGET", "SOURCE_UNREACHABLE"));
        out.put("missingTables", run == null ? List.of() : issueObjects(run.issuesJson(), Set.of("TABLE_MISSING")));
        out.put("missingColumns", run == null ? List.of() : issueObjects(run.issuesJson(), Set.of("COLUMN_MISSING")));
        out.put("changedColumns", run == null ? List.of() : issueObjects(run.issuesJson(), Set.of(
                "DATA_TYPE_CHANGED", "LENGTH_NARROWED", "LENGTH_WIDENED", "SCALE_CHANGED",
                "NULLABILITY_TIGHTENED", "NULLABILITY_RELAXED", "GENERATED_STATE_CHANGED", "DEFAULT_CHANGED")));
        out.put("schedule", schedule(definition.getId()));
        return out;
    }

    private Map<String, Object> summary(List<DriftIssue> issues, SchemaSnapshot expected, SchemaSnapshot actual) {
        Map<String, Integer> severity = new LinkedHashMap<>();
        for (String level : List.of("BLOCKER", "CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO")) severity.put(level, 0);
        Map<String, Integer> scopes = new LinkedHashMap<>(Map.of("SOURCE", 0, "TARGET", 0));
        Map<String, Integer> changes = new LinkedHashMap<>();
        for (DriftIssue issue : issues) {
            severity.compute(issue.severity(), (key, value) -> value == null ? 1 : value + 1);
            scopes.compute(issue.scope(), (key, value) -> value == null ? 1 : value + 1);
            changes.compute(issue.type(), (key, value) -> value == null ? 1 : value + 1);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("issueCount", issues.size());
        out.put("blockingCount", issues.stream().filter(i -> BLOCKING_SEVERITIES.contains(i.severity())).count());
        out.put("severityCounts", severity);
        out.put("scopeCounts", scopes);
        out.put("changeCounts", changes);
        out.put("baselineTables", expected.tables().size());
        out.put("currentTables", actual.tables().size());
        return out;
    }

    private Map<String, Object> emptySummary() {
        return Map.of("issueCount", 0, "blockingCount", 0,
                "severityCounts", Map.of("BLOCKER", 0, "CRITICAL", 0, "HIGH", 0, "MEDIUM", 0, "LOW", 0, "INFO", 0),
                "scopeCounts", Map.of("SOURCE", 0, "TARGET", 0), "changeCounts", Map.of());
    }

    private BaselineRow activeBaseline(long datasetId) {
        List<BaselineRow> rows = jdbc.query("SELECT id, version_no, fingerprint, snapshot_json, acceptance_reason, " +
                        "accepted_by, accepted_at FROM schema_drift_baselines WHERE dataset_id = ? AND active = TRUE " +
                        "ORDER BY version_no DESC",
                (rs, row) -> new BaselineRow(rs.getLong("id"), rs.getInt("version_no"),
                        rs.getString("fingerprint"), rs.getString("snapshot_json"),
                        rs.getString("acceptance_reason"), rs.getString("accepted_by"),
                        instant(rs, "accepted_at")), datasetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RunRow latestRun(long datasetId) {
        List<RunRow> rows = jdbc.query("SELECT id, baseline_id, trigger_type, status, fingerprint, issue_count, " +
                        "blocking_count, summary_json, issues_json, snapshot_json, checked_by, checked_at " +
                        "FROM schema_drift_runs WHERE dataset_id = ? ORDER BY checked_at DESC FETCH FIRST 1 ROWS ONLY",
                (rs, row) -> mapRun(rs), datasetId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private RunRow run(long datasetId, long runId) {
        List<RunRow> rows = jdbc.query("SELECT id, baseline_id, trigger_type, status, fingerprint, issue_count, " +
                        "blocking_count, summary_json, issues_json, snapshot_json, checked_by, checked_at " +
                        "FROM schema_drift_runs WHERE dataset_id = ? AND id = ?",
                (rs, row) -> mapRun(rs), datasetId, runId);
        if (rows.isEmpty()) throw ApiException.notFound("Schema drift scan " + runId + " not found");
        return rows.get(0);
    }

    private RunRow mapRun(ResultSet rs) throws SQLException {
        return new RunRow(rs.getLong("id"), nullableLong(rs, "baseline_id"), rs.getString("trigger_type"),
                rs.getString("status"), rs.getString("fingerprint"), rs.getInt("issue_count"),
                rs.getInt("blocking_count"), rs.getString("summary_json"), rs.getString("issues_json"),
                rs.getString("snapshot_json"), rs.getString("checked_by"), instant(rs, "checked_at"));
    }

    private Map<String, Object> baselineMap(BaselineRow baseline) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", baseline.id());
        out.put("version", baseline.version());
        out.put("fingerprint", baseline.fingerprint());
        out.put("reason", baseline.reason());
        out.put("acceptedBy", baseline.acceptedBy());
        out.put("acceptedAt", baseline.acceptedAt());
        return out;
    }

    private Map<String, Object> scheduleRow(ResultSet rs) throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", rs.getBoolean("enabled"));
        out.put("cron", rs.getString("cron_expression"));
        out.put("zone", rs.getString("zone_id"));
        out.put("nextRunAt", instant(rs, "next_run_at"));
        out.put("lastRunAt", instant(rs, "last_run_at"));
        out.put("configuredBy", rs.getString("configured_by"));
        out.put("updatedAt", instant(rs, "updated_at"));
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private DataSetDefinitionEntity definition(long datasetId) {
        DataSetDefinitionEntity definition = definitions.findById(datasetId)
                .orElseThrow(() -> ApiException.notFound("DataScope " + datasetId + " not found"));
        ownership.assertCanSee("DataScope", datasetId, definition.getOwnerUserId(),
                definition.getOwnerGroupId(), definition.getVisibility());
        return definition;
    }

    private DataSetDefinitionEntity monitorDefinition(long monitorId) {
        DataSetDefinitionEntity definition = definition(monitorId);
        if (!definition.isDriftMonitorOnly()) {
            throw ApiException.notFound("Schema monitor " + monitorId + " not found");
        }
        return definition;
    }

    private Map<String, Object> monitorSummary(DataSetDefinitionEntity monitor) {
        Map<String, Object> report = report(monitor, activeBaseline(monitor.getId()), latestRun(monitor.getId()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", monitor.getId());
        out.put("name", monitor.getName());
        out.put("description", monitor.getDescription());
        out.put("dataSourceId", monitor.getDataSourceId());
        try {
            DataSourceEntity source = dataSources.get(monitor.getDataSourceId());
            out.put("dataSourceName", source.getName());
            out.put("engine", SqlDialect.of(source).name());
        } catch (RuntimeException unavailable) {
            out.put("dataSourceName", "Unavailable data source");
            out.put("engine", "UNKNOWN");
        }
        out.put("schemaName", monitor.getSchemaName());
        out.put("createdAt", monitor.getCreatedAt());
        out.put("updatedAt", monitor.getUpdatedAt());
        out.put("report", report);
        return out;
    }

    private static String trimTo(String value, int maxLength) {
        String cleaned = clean(value);
        return cleaned == null ? null : cleaned.substring(0, Math.min(cleaned.length(), maxLength));
    }

    private List<String> affectedJobs(long datasetId) {
        try {
            List<String> names = new ArrayList<>();
            jdbc.query("SELECT name, spec_json FROM datascope_saved_jobs", rs -> {
                try {
                    JsonNode spec = json.readTree(rs.getString("spec_json"));
                    if (containsDataset(spec, datasetId) && names.size() < 25) names.add(rs.getString("name"));
                } catch (Exception ignored) { }
            });
            return List.copyOf(names);
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static boolean containsDataset(JsonNode node, long datasetId) {
        if (node == null || node.isNull()) return false;
        if (node.isObject()) {
            JsonNode value = node.get("datasetId");
            if (value != null && value.canConvertToLong() && value.asLong() == datasetId) return true;
            var fields = node.fields();
            while (fields.hasNext()) if (containsDataset(fields.next().getValue(), datasetId)) return true;
        } else if (node.isArray()) {
            for (JsonNode child : node) if (containsDataset(child, datasetId)) return true;
        }
        return false;
    }

    private String driftOverrideReason(String specJson) {
        if (specJson == null || specJson.isBlank()) return null;
        try { return clean(json.readTree(specJson).path("driftOverrideReason").asText(null)); }
        catch (Exception ignored) { return null; }
    }

    private Map<String, TableSnapshot> index(List<TableSnapshot> tables) {
        Map<String, TableSnapshot> out = new LinkedHashMap<>();
        for (TableSnapshot table : tables) out.put(tableKey(table), table);
        return out;
    }

    private static String tableKey(TableSnapshot table) {
        return table.scope() + "|" + table.dataSourceId() + "|" + lower(table.schema()) + "|" + lower(table.logicalTable());
    }

    private static String describe(ColumnSnapshot column) {
        return column.typeName() + "(" + column.length() + (column.scale() == null ? "" : "," + column.scale()) + ")"
                + (column.nullable() ? " NULL" : " NOT NULL");
    }

    private static boolean typeEquivalent(ColumnSnapshot left, ColumnSnapshot right) {
        return left.jdbcType() == right.jdbcType() && lower(left.typeName()).equals(lower(right.typeName()));
    }

    private String canonical(Object value) {
        return write(value).toLowerCase(Locale.ROOT);
    }

    private String fingerprint(SchemaSnapshot snapshot) {
        return sha256(write(snapshot.tables()));
    }

    private static List<IndexSnapshot> uniqueIndexes(List<IndexSnapshot> indexes) {
        return indexes.stream().filter(IndexSnapshot::unique).toList();
    }

    private static boolean sameNames(List<String> left, List<String> right) {
        if (left.size() != right.size()) return false;
        for (int i = 0; i < left.size(); i++) if (!left.get(i).equalsIgnoreCase(right.get(i))) return false;
        return true;
    }

    private static boolean sameExpression(String left, String right) {
        return Objects.equals(normalizeExpression(left), normalizeExpression(right));
    }

    private static String normalizeExpression(String expression) {
        return expression == null ? null : expression.replaceAll("\\s+", " ").trim();
    }

    private static int severityRank(String severity) {
        return switch (severity) {
            case "BLOCKER" -> 6;
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            default -> 1;
        };
    }

    private static String normalizeTrigger(String trigger) {
        String value = clean(trigger);
        if (value == null) return "MANUAL";
        value = value.toUpperCase(Locale.ROOT);
        return Set.of("MANUAL", "SCHEDULED", "PRE_PROVISION").contains(value) ? value : "MANUAL";
    }

    private static String requireReason(String reason) {
        String cleaned = clean(reason);
        if (cleaned == null || cleaned.length() < 8)
            throw ApiException.bad("Provide an acceptance reason of at least 8 characters");
        if (cleaned.length() > 1000) throw ApiException.bad("Acceptance reason cannot exceed 1000 characters");
        return cleaned;
    }

    private String actor() {
        return AccessContext.current().map(p -> p.username()).orElse("system");
    }

    private ZoneId zone(String value) {
        try { return ZoneId.of(clean(value) == null ? "UTC" : value.trim()); }
        catch (Exception e) { throw ApiException.bad("Unknown time zone '" + value + "'"); }
    }

    private Instant nextRun(String cron, ZoneId zone, Instant from) {
        CronExpression expression;
        try { expression = CronExpression.parse(cron); }
        catch (Exception e) { throw ApiException.bad("Invalid cron expression: " + e.getMessage()); }
        ZonedDateTime next = expression.next(from.atZone(zone));
        if (next == null) throw ApiException.bad("Cron expression has no future run time");
        return next.toInstant();
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalStateException("Could not serialize schema drift evidence", e); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception e) { throw new IllegalStateException("Could not read schema drift evidence", e); }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try { return json.readValue(value, Map.class); }
        catch (Exception e) { return Map.of(); }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readList(String value) {
        try { return json.readValue(value, List.class); }
        catch (Exception e) { return List.of(); }
    }

    private List<Map<String, Object>> issueObjects(String value, Set<String> types) {
        return readList(value).stream().filter(issue -> types.contains(String.valueOf(issue.get("type")))).toList();
    }

    private boolean containsIssue(String value, String scope, String type) {
        return readList(value).stream().anyMatch(issue -> scope.equals(issue.get("scope")) && type.equals(issue.get("type")));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static Map<String, Object> scheduleDefault() { return Map.of("enabled", false, "zone", "UTC"); }
    private static String lower(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String clean(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }
    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
    private static String safeString(ResultSet rs, String column) {
        try { return rs.getString(column); }
        catch (SQLException ignored) { return null; }
    }
    private static String rsSafe(ResultSet rs, String column) {
        try { return rs.getString(column); }
        catch (SQLException ignored) { return null; }
    }
    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return firstText(current.getMessage(), current.getClass().getSimpleName());
    }

    private static final class MutableForeignKey {
        private final String name;
        private final String parentSchema;
        private final String parentTable;
        private final short updateRule;
        private final short deleteRule;
        private final TreeMap<Short, String> child = new TreeMap<>();
        private final TreeMap<Short, String> parent = new TreeMap<>();

        private MutableForeignKey(String name, String parentSchema, String parentTable,
                                  short updateRule, short deleteRule) {
            this.name = name;
            this.parentSchema = parentSchema;
            this.parentTable = parentTable;
            this.updateRule = updateRule;
            this.deleteRule = deleteRule;
        }

        private ForeignKeySnapshot freeze() {
            return new ForeignKeySnapshot(name, List.copyOf(child.values()), parentSchema, parentTable,
                    List.copyOf(parent.values()), updateRule, deleteRule);
        }
    }

    private static final class MutableIndex {
        private final String name;
        private final boolean unique;
        private final TreeMap<Short, String> columns = new TreeMap<>();

        private MutableIndex(String name, boolean unique) { this.name = name; this.unique = unique; }
        private IndexSnapshot freeze() { return new IndexSnapshot(name, unique, List.copyOf(columns.values())); }
    }
}
