package io.forgetdm.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.DataSourceEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Blob;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;

/**
 * Native Db2 LUW and Db2 for z/OS CDC through IBM SQL Replication.
 *
 * <p>The IBM Capture program reads the Db2 recovery log and writes committed row images to the
 * registered CD tables. Forge reads those CD tables by commit sequence; it never rescans the source
 * business tables. Registrations and CAPSTART activation remain standard IBM replication objects,
 * which keeps the implementation compatible with DBA-operated Db2 environments.</p>
 */
@Component
public class Db2SqlReplicationCdcProvider implements CdcProvider {

    private static final int REGISTRATION_UNION_SIZE = 100;
    private static final Set<String> META_COLUMNS = Set.of(
            "IBMSNAP_COMMITSEQ", "IBMSNAP_INTENTSEQ", "IBMSNAP_OPERATION");

    private final String defaultCaptureSchema;
    private final ObjectMapper json;

    public Db2SqlReplicationCdcProvider(
            @Value("${forgetdm.cdc.db2-capture-schema:ASN}") String captureSchema,
            ObjectMapper json) {
        this.defaultCaptureSchema = normalizeControlSchema(captureSchema, "ASN");
        this.json = json;
    }

    @Override
    public boolean supports(DataSourceEntity ds) {
        String kind = lower(ds.getKind());
        String url = lower(ds.getJdbcUrl());
        return kind.equals("db2") || kind.contains("db2udb") || kind.contains("db2luw")
                || isZos(ds)
                || url.startsWith("jdbc:db2:");
    }

    @Override
    public String mechanism() { return "IBM Db2 SQL Replication (recovery log)"; }

    @Override
    public String mechanism(DataSourceEntity ds) {
        return isZos(ds)
                ? "IBM Db2 for z/OS SQL Capture (recovery log / CD tables)"
                : mechanism();
    }

    @Override
    public String pluginName() { return "ibm_sql_replication"; }

    @Override
    public Preflight preflight(DataSourceEntity ds) {
        return preflight(ds, new CaptureOptions(defaultCaptureSchema));
    }

    @Override
    public Preflight preflight(DataSourceEntity ds, CaptureOptions options) {
        String captureSchema = controlSchema(options);
        List<String> messages = new ArrayList<>();
        try (Connection c = open(ds)) {
            RegistrationCounts counts = registrationCounts(c, captureSchema);
            if (counts.total == 0) {
                messages.add("No IBM SQL Replication registrations exist in capture schema "
                        + captureSchema + ". Create Capture control tables and register the source "
                        + "tables with ASNCLP before enabling CDC. Source tables must use DATA CAPTURE CHANGES.");
            } else if (counts.active == 0) {
                messages.add(counts.total + " registration(s) exist but none are active. Start the "
                        + "IBM Capture program and complete the supported CAPSTART handshake.");
            }
            if (counts.stopped > 0) {
                messages.add(counts.stopped + " registration(s) are stopped by IBM Capture. Review "
                        + "the asncap log, repair the registration, and reactivate it.");
            }

            Timestamp checkpointTs = latestCaptureTime(c, captureSchema);
            if (checkpointTs == null) {
                messages.add("No IBM Capture restart checkpoint is visible. Verify that asncap is "
                        + "running for database " + ds.getName() + " and capture schema " + captureSchema + ".");
            } else {
                long age = Math.max(0, Duration.between(checkpointTs.toInstant(), Instant.now()).toSeconds());
                if (age > 300) {
                    messages.add("The latest IBM Capture checkpoint is " + age + " seconds old. "
                            + "Verify asncap health before relying on low-latency delivery.");
                }
            }

            String archive = isZos(ds) ? null : dbConfig(c, "logarchmeth1");
            String platform = isZos(ds) ? "Db2 z/OS SQL Capture" : "Db2 LUW SQL Capture";
            String status = (archive == null ? platform : "LOGARCHMETH1=" + archive)
                    + "; active registrations " + counts.active + "/" + counts.total;
            if (isZos(ds) && counts.active > 0 && counts.stopped == 0) {
                messages.add("Db2 z/OS registration and restart checkpoints are ready. IFI 306 filtering is an optional DBA-operated Capture performance setting; Forge does not alter subsystem parameters.");
            }
            return new Preflight(counts.active > 0 && counts.stopped == 0,
                    status, true, messages);
        } catch (Exception e) {
            throw ApiException.bad("Db2 CDC preflight failed: " + rootMessage(e)
                    + ". Verify SELECT access to " + captureSchema
                    + ".IBMSNAP_REGISTER, IBMSNAP_RESTART, IBMSNAP_UOW and the registered CD tables.");
        }
    }

    @Override
    public void validateScope(DataSourceEntity ds, String schema, List<String> tables) {
        validateScope(ds, schema, tables, new CaptureOptions(defaultCaptureSchema));
    }

    @Override
    public void validateScope(DataSourceEntity ds, String schema, List<String> tables,
                              CaptureOptions options) {
        String captureSchema = controlSchema(options);
        try (Connection c = open(ds)) {
            List<Registration> registered = registrations(c, captureSchema, schema, tables, false);
            List<Registration> active = registered.stream()
                    .filter(registration -> "A".equalsIgnoreCase(registration.state))
                    .toList();
            if (active.isEmpty()) {
                String requested = requestedScope(schema, tables);
                throw ApiException.bad("No active IBM SQL Replication registration covers " + requested
                        + ". Register those Db2 tables and complete CAPSTART before enabling Forge CDC.");
            }
            Set<String> requested = normalizedRequestedTables(schema, tables);
            if (!requested.isEmpty()) {
                Set<String> activeTables = new LinkedHashSet<>();
                for (Registration r : active) {
                    activeTables.add(r.qualifiedSource().toLowerCase(Locale.ROOT));
                }
                List<String> missing = requested.stream()
                        .filter(table -> !activeTables.contains(table)).toList();
                if (!missing.isEmpty()) {
                    throw ApiException.bad("IBM SQL Replication is not active for: "
                            + String.join(", ", missing) + ". Register and CAPSTART every requested table.");
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Could not validate Db2 CDC table registrations: " + rootMessage(e));
        }
    }

    @Override
    public String currentLogPosition(DataSourceEntity ds) {
        try (Connection c = open(ds)) { return currentPosition(c, defaultCaptureSchema); }
        catch (Exception e) { return null; }
    }

    @Override
    public String currentLogPosition(DataSourceEntity ds, CdcCaptureEntity capture) {
        try (Connection c = open(ds)) { return currentPosition(c, controlSchema(capture)); }
        catch (Exception e) { return null; }
    }

    @Override
    public Long lag(DataSourceEntity ds, String confirmedPosition) {
        return lag(ds, defaultCaptureSchema, confirmedPosition);
    }

    @Override
    public Long lag(DataSourceEntity ds, CdcCaptureEntity capture, String confirmedPosition) {
        return lag(ds, controlSchema(capture), confirmedPosition);
    }

    private Long lag(DataSourceEntity ds, String captureSchema, String confirmedPosition) {
        if (!isCommitSequence(confirmedPosition)) return null;
        String sql = "SELECT COUNT(*) FROM " + q(captureSchema)
                + ".IBMSNAP_UOW WHERE HEX(IBMSNAP_COMMITSEQ) > ?";
        try (Connection c = open(ds); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, confirmedPosition.toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getLong(1) : null; }
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String lagUnit() { return "commits"; }

    @Override
    public SlotInfo createSlot(DataSourceEntity ds, String slotName) {
        return createSlot(ds, slotName, new CaptureOptions(defaultCaptureSchema));
    }

    @Override
    public SlotInfo createSlot(DataSourceEntity ds, String slotName, CaptureOptions options) {
        String captureSchema = controlSchema(options);
        try (Connection c = open(ds)) {
            String position = currentPosition(c, captureSchema);
            String prefix = isZos(ds) ? "asn-zos:" : "asn:";
            return new SlotInfo(prefix + captureSchema.toLowerCase(Locale.ROOT) + ":" + slotName,
                    position, position);
        } catch (Exception e) {
            throw ApiException.bad("Cannot establish the Db2 CDC checkpoint: " + rootMessage(e));
        }
    }

    @Override
    public void dropSlot(DataSourceEntity ds, String slotName) {
        // IBM registrations can be shared with an enterprise replication topology. Disabling a
        // Forge consumer therefore stops only Forge polling and never CAPSTOPs the DBA-owned source.
    }

    @Override
    public PollResult poll(DataSourceEntity ds, CdcCaptureEntity capture,
                           int maxChanges, long budgetMillis) {
        String after = normalizePosition(capture.getConfirmedLsn());
        String captureSchema = controlSchema(capture);
        int limit = Math.max(1, maxChanges);
        try (Connection c = open(ds)) {
            List<Registration> registrations = registrations(
                    c, captureSchema, capture.getSchemaName(), readTables(capture.getTablesJson()), true);
            if (registrations.isEmpty()) {
                throw ApiException.bad("No active IBM SQL Replication registration matches the "
                        + "configured Db2 CDC scope.");
            }

            List<Pointer> pointers = firstPointers(c, registrations, after, limit + 1);
            String current = currentPosition(c, captureSchema);
            if (pointers.isEmpty()) return new PollResult(List.of(), current, true);

            pointers.sort(Comparator.comparing(Pointer::commitSeq).thenComparing(Pointer::intentSeq));
            boolean reachedEnd = pointers.size() <= limit;
            String boundary = reachedEnd ? current : pointers.get(limit - 1).commitSeq;

            List<CapturedRow> rows = new ArrayList<>();
            Map<String, List<String>> pkCache = new LinkedHashMap<>();
            // Read every registration through the global boundary. A commit can span more tables
            // than the requested row limit; restricting this pass to registrations visible in the
            // limited pointer sample could otherwise split that transaction.
            for (Registration registration : registrations) {
                rows.addAll(readRows(c, registration, after, boundary, pkCache));
            }
            rows.sort(Comparator.comparing(CapturedRow::commitSeq)
                    .thenComparing(CapturedRow::intentSeq));
            return new PollResult(rows.stream().map(CapturedRow::change).toList(), boundary, reachedEnd);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Db2 CDC poll failed: " + rootMessage(e));
        }
    }

    private List<Pointer> firstPointers(Connection c, List<Registration> registrations,
                                        String after, int fetchLimit) throws SQLException {
        List<Pointer> pointers = new ArrayList<>();
        for (int start = 0; start < registrations.size(); start += REGISTRATION_UNION_SIZE) {
            int end = Math.min(registrations.size(), start + REGISTRATION_UNION_SIZE);
            StringBuilder sql = new StringBuilder("SELECT COMMITSEQ, INTENTSEQ, REG_INDEX FROM (");
            for (int i = start; i < end; i++) {
                if (i > start) sql.append(" UNION ALL ");
                Registration r = registrations.get(i);
                sql.append("SELECT HEX(IBMSNAP_COMMITSEQ) COMMITSEQ, ")
                        .append("HEX(IBMSNAP_INTENTSEQ) INTENTSEQ, ")
                        .append(i).append(" REG_INDEX FROM ")
                        .append(q(r.cdOwner)).append('.').append(q(r.cdTable))
                        .append(" WHERE HEX(IBMSNAP_COMMITSEQ) > ?");
            }
            sql.append(") FDM ORDER BY COMMITSEQ, INTENTSEQ FETCH FIRST ")
                    .append(fetchLimit).append(" ROWS ONLY");
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                for (int i = start; i < end; i++) ps.setString(i - start + 1, after);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        pointers.add(new Pointer(rs.getString(1), rs.getString(2), rs.getInt(3)));
                    }
                }
            }
        }
        return pointers;
    }

    private List<CapturedRow> readRows(Connection c, Registration registration,
                                       String after, String boundary,
                                       Map<String, List<String>> pkCache) throws SQLException {
        String sql = "SELECT HEX(C.IBMSNAP_COMMITSEQ) FDM_COMMITSEQ, "
                + "HEX(C.IBMSNAP_INTENTSEQ) FDM_INTENTSEQ, C.IBMSNAP_OPERATION FDM_OPERATION, C.* FROM "
                + q(registration.cdOwner) + "." + q(registration.cdTable) + " C "
                + "WHERE HEX(C.IBMSNAP_COMMITSEQ) > ? AND HEX(C.IBMSNAP_COMMITSEQ) <= ? "
                + "ORDER BY C.IBMSNAP_COMMITSEQ, C.IBMSNAP_INTENTSEQ";
        List<CapturedRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, after);
            ps.setString(2, boundary);
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    String commit = rs.getString("FDM_COMMITSEQ");
                    String intent = rs.getString("FDM_INTENTSEQ");
                    DecodedChange change = new DecodedChange();
                    change.lsn = commit;
                    change.schema = registration.sourceOwner;
                    change.table = registration.sourceTable;
                    change.op = normalizeOperation(rs.getString("FDM_OPERATION"));
                    if (change.op == null) continue;

                    for (int i = 4; i <= md.getColumnCount(); i++) {
                        String column = md.getColumnLabel(i);
                        if (META_COLUMNS.contains(column.toUpperCase(Locale.ROOT))) continue;
                        change.values.put(column, readValue(rs, i, md.getColumnType(i)));
                    }
                    attachPk(c, change, registration, pkCache);
                    rows.add(new CapturedRow(commit, intent, change));
                }
            }
        }
        return rows;
    }

    private void attachPk(Connection c, DecodedChange change, Registration registration,
                          Map<String, List<String>> pkCache) {
        List<String> pkColumns = pkCache.computeIfAbsent(registration.qualifiedSource(), ignored ->
                loadPk(c, registration.sourceOwner, registration.sourceTable));
        for (String pk : pkColumns) {
            String actual = change.values.keySet().stream()
                    .filter(key -> key.equalsIgnoreCase(pk)).findFirst().orElse(null);
            if (actual != null) change.pk.put(actual, change.values.get(actual));
        }
    }

    private List<String> loadPk(Connection c, String schema, String table) {
        TreeMap<Short, String> ordered = new TreeMap<>();
        try {
            DatabaseMetaData metadata = c.getMetaData();
            try (ResultSet rs = metadata.getPrimaryKeys(null, schema, table)) {
                while (rs.next()) ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        } catch (Exception ignored) { }
        return new ArrayList<>(ordered.values());
    }

    private List<Registration> registrations(Connection c, String captureSchema,
                                             String requestedSchema,
                                             List<String> requestedTables, boolean activeOnly)
            throws SQLException {
        String sql = "SELECT SOURCE_OWNER, SOURCE_TABLE, CD_OWNER, CD_TABLE, STATE FROM "
                + q(captureSchema) + ".IBMSNAP_REGISTER "
                + "WHERE SOURCE_OWNER IS NOT NULL AND SOURCE_TABLE IS NOT NULL "
                + "AND CD_OWNER IS NOT NULL AND CD_TABLE IS NOT NULL "
                + (activeOnly ? "AND STATE = 'A' " : "")
                + "ORDER BY SOURCE_OWNER, SOURCE_TABLE";
        Set<String> requested = normalizedRequestedTables(requestedSchema, requestedTables);
        String schema = requestedSchema == null ? "" : requestedSchema.trim();
        List<Registration> out = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                Registration r = new Registration(rs.getString(1).trim(), rs.getString(2).trim(),
                        rs.getString(3).trim(), rs.getString(4).trim(), rs.getString(5).trim());
                if (!schema.isBlank() && !r.sourceOwner.equalsIgnoreCase(schema)) continue;
                if (!requested.isEmpty()
                        && !requested.contains(r.qualifiedSource().toLowerCase(Locale.ROOT))) continue;
                out.add(r);
            }
        }
        return out;
    }

    private RegistrationCounts registrationCounts(Connection c, String captureSchema) throws SQLException {
        String sql = "SELECT COUNT(*), "
                + "COALESCE(SUM(CASE WHEN STATE='A' THEN 1 ELSE 0 END),0), "
                + "COALESCE(SUM(CASE WHEN STATE='S' THEN 1 ELSE 0 END),0) FROM "
                + q(captureSchema) + ".IBMSNAP_REGISTER WHERE SOURCE_OWNER IS NOT NULL";
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return new RegistrationCounts(rs.getInt(1), rs.getInt(2), rs.getInt(3));
        }
    }

    private String currentPosition(Connection c, String captureSchema) throws SQLException {
        String uow = scalar(c, "SELECT MAX(HEX(IBMSNAP_COMMITSEQ)) FROM "
                + q(captureSchema) + ".IBMSNAP_UOW");
        if (isCommitSequence(uow)) return uow;
        String restart = scalar(c, "SELECT MAX(HEX(MAX_COMMITSEQ)) FROM "
                + q(captureSchema) + ".IBMSNAP_RESTART");
        return isCommitSequence(restart) ? restart : zeroPosition();
    }

    private Timestamp latestCaptureTime(Connection c, String captureSchema) {
        try {
            String sql = "SELECT MAX(CURR_COMMIT_TIME) FROM " + q(captureSchema) + ".IBMSNAP_RESTART";
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
                return rs.next() ? rs.getTimestamp(1) : null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String dbConfig(Connection c, String name) {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT VALUE FROM SYSIBMADM.DBCFG WHERE LOWER(NAME) = ?")) {
            ps.setString(1, name.toLowerCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : null; }
        } catch (Exception e) {
            return null;
        }
    }

    private Connection open(DataSourceEntity ds) throws SQLException {
        Properties properties = new Properties();
        if (ds.getUsername() != null) properties.setProperty("user", ds.getUsername());
        if (ds.getPassword() != null) properties.setProperty("password", ds.getPassword());
        return DriverManager.getConnection(ds.getJdbcUrl(), properties);
    }

    private String controlSchema(CaptureOptions options) {
        return normalizeControlSchema(options == null ? null : options.controlSchema(), defaultCaptureSchema);
    }

    private String controlSchema(CdcCaptureEntity capture) {
        return normalizeControlSchema(capture == null ? null : capture.getControlSchema(), defaultCaptureSchema);
    }

    static String normalizeControlSchema(String value, String fallback) {
        String schema = value == null || value.isBlank() ? fallback : value.trim();
        if (schema == null || schema.isBlank()) schema = "ASN";
        if (!schema.matches("[A-Za-z_][A-Za-z0-9_@$#]{0,127}")) {
            throw ApiException.bad("IBM Capture control schema must be an unquoted Db2 identifier (letters, digits, _, @, $, or #; maximum 128 characters).");
        }
        return schema.toUpperCase(Locale.ROOT);
    }

    static boolean isZos(DataSourceEntity ds) {
        String kind = lower(ds == null ? null : ds.getKind());
        return kind.contains("db2zos") || kind.contains("db2_zos")
                || kind.contains("db2 z/os") || kind.contains("z/os");
    }

    private static String readValue(ResultSet rs, int index, int type) throws SQLException {
        if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY) {
            byte[] value = rs.getBytes(index);
            return value == null ? null : Base64.getEncoder().encodeToString(value);
        }
        if (type == Types.BLOB) {
            Blob blob = rs.getBlob(index);
            if (blob == null) return null;
            try {
                byte[] bytes = blob.getBytes(1, Math.toIntExact(blob.length()));
                return Base64.getEncoder().encodeToString(bytes);
            } finally {
                blob.free();
            }
        }
        return rs.getString(index);
    }

    static String normalizeOperation(String operation) {
        if (operation == null) return null;
        return switch (operation.trim().toUpperCase(Locale.ROOT)) {
            case "I" -> "I";
            case "U" -> "U";
            case "D" -> "D";
            default -> null;
        };
    }

    static boolean isCommitSequence(String position) {
        return position != null && position.trim().matches("(?i)[0-9a-f]{32}");
    }

    private static String normalizePosition(String position) {
        return isCommitSequence(position) ? position.trim().toUpperCase(Locale.ROOT) : zeroPosition();
    }

    private static String zeroPosition() { return "00000000000000000000000000000000"; }

    private static Set<String> normalizedRequestedTables(String schema, List<String> tables) {
        if (tables == null || tables.isEmpty()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        String defaultSchema = schema == null ? "" : schema.trim();
        for (String raw : tables) {
            if (raw == null || raw.isBlank()) continue;
            String table = raw.trim().replace("\"", "");
            if (!table.contains(".") && !defaultSchema.isBlank()) table = defaultSchema + "." + table;
            out.add(table.toLowerCase(Locale.ROOT));
        }
        return out;
    }

    private static String requestedScope(String schema, List<String> tables) {
        Set<String> requested = normalizedRequestedTables(schema, tables);
        if (!requested.isEmpty()) return String.join(", ", requested);
        return schema == null || schema.isBlank() ? "the requested source" : "schema " + schema;
    }

    private List<String> readTables(String tablesJson) {
        if (tablesJson == null || tablesJson.isBlank()) return List.of();
        try {
            String[] values = json.readValue(tablesJson, String[].class);
            if (values == null || values.length == 0) return List.of();
            List<String> tables = new ArrayList<>();
            for (String value : values) {
                if (value != null && !value.isBlank()) tables.add(value.trim());
            }
            return tables;
        } catch (Exception e) {
            throw ApiException.bad("The saved Db2 CDC table scope is invalid JSON. Re-save the "
                    + "capture definition before polling.");
        }
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static String q(String identifier) {
        return "\"" + (identifier == null ? "" : identifier.replace("\"", "\"\"")) + "\"";
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.toString() : root.getMessage();
    }

    private record Registration(String sourceOwner, String sourceTable,
                                String cdOwner, String cdTable, String state) {
        String qualifiedSource() { return sourceOwner + "." + sourceTable; }
    }

    private record RegistrationCounts(int total, int active, int stopped) { }

    private record Pointer(String commitSeq, String intentSeq, int registrationIndex) { }

    private record CapturedRow(String commitSeq, String intentSeq, DecodedChange change) { }
}
