package io.forgetdm.query;

import io.forgetdm.audit.AuditService;
import io.forgetdm.common.ApiException;
import io.forgetdm.datasource.ConnectionFactory;
import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.forgetdm.query.QueryController.*;

/**
 * Data Explorer service.
 *
 * <p>The SQL console remains read-only. Row CRUD uses a separate metadata-driven contract: every
 * schema, table, and column must resolve through JDBC metadata, values are bound parameters, and
 * update/delete require the complete physical primary key. No executable DML text crosses the API.
 */
@Service
public class QueryService {

    public static final int MAX_ROWS = 1000;
    private static final int CELL_CAP = 1000;
    private static final int QUERY_TIMEOUT_SEC = 30;
    private static final int TABLE_PAGE_SIZE = 100;
    private static final int TABLE_PAGE_SIZE_MAX = 250;
    private static final int TABLE_OFFSET_MAX = 1_000_000;
    private static final Set<String> CONSOLE_STATEMENTS = Set.of(
            "SELECT", "WITH", "CREATE", "ALTER", "INSERT", "UPDATE", "DELETE", "MERGE", "TRUNCATE", "DROP"
    );
    private static final Pattern FIRST_KEYWORD = Pattern.compile(
            "(?is)^(?:\\s|--[^\\r\\n]*(?:\\r?\\n|$)|/\\*.*?\\*/)*([a-z]+)"
    );

    private final DataSourceService dataSources;
    private final ConnectionFactory connections;
    private final AuditService audit;

    public QueryService(DataSourceService dataSources, ConnectionFactory connections, AuditService audit) {
        this.dataSources = dataSources;
        this.connections = connections;
        this.audit = audit;
    }

    public Map<String, Object> run(Long dataSourceId, String sql) {
        if (dataSourceId == null) throw ApiException.bad("dataSourceId is required");
        if (sql == null || sql.isBlank()) throw ApiException.bad("A SELECT query is required");

        String stmt = singleStatement(sql);
        String lower = stmt.toLowerCase(Locale.ROOT);
        if (!(lower.startsWith("select") || lower.startsWith("with"))) {
            throw ApiException.bad("Only read-only SELECT (or WITH ... SELECT) queries are allowed here.");
        }

        DataSourceEntity ds = dataSources.get(dataSourceId);
        long start = System.currentTimeMillis();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;

        try (Connection c = connections.openPooled(ds)) {
            try { c.setReadOnly(true); } catch (Exception ignored) { }
            try { c.setAutoCommit(false); } catch (Exception ignored) { }
            try (Statement st = c.createStatement()) {
                st.setMaxRows(MAX_ROWS + 1);
                try { st.setQueryTimeout(QUERY_TIMEOUT_SEC); } catch (Exception ignored) { }
                try (ResultSet rs = st.executeQuery(stmt)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int n = md.getColumnCount();
                    for (int i = 1; i <= n; i++) columns.add(md.getColumnLabel(i));
                    while (rs.next()) {
                        if (rows.size() >= MAX_ROWS) {
                            truncated = true;
                            break;
                        }
                        List<Object> row = new ArrayList<>(n);
                        for (int i = 1; i <= n; i++) row.add(cell(rs, i));
                        rows.add(row);
                    }
                }
            }
            try { c.rollback(); } catch (Exception ignored) { }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Query failed: " + rootMessage(e));
        }

        audit.log("system", "QUERY_RUN", "ds=" + dataSourceId + " rows=" + rows.size());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        out.put("truncated", truncated);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    /**
     * Governed SQL console execution for users with datasource.manage.
     *
     * <p>Only one allow-listed statement is accepted. The statement runs on a direct connection,
     * commits on success, rolls back on failure, and records a digest rather than SQL text so
     * literals and sensitive data do not leak into the audit ledger.
     */
    public Map<String, Object> execute(Long dataSourceId, String sql) {
        if (dataSourceId == null) throw ApiException.bad("dataSourceId is required");
        if (sql == null || sql.isBlank()) throw ApiException.bad("A SQL statement is required");
        String stmt = singleStatement(sql);
        String kind = statementKind(stmt);
        if (!CONSOLE_STATEMENTS.contains(kind)) {
            throw ApiException.bad("Unsupported SQL statement. Allowed: " + String.join(", ", CONSOLE_STATEMENTS));
        }

        DataSourceEntity ds = dataSources.get(dataSourceId);
        long start = System.currentTimeMillis();
        List<String> columns = new ArrayList<>();
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        int affectedRows = -1;

        try (Connection c = connections.open(ds)) {
            c.setAutoCommit(false);
            try (Statement statement = c.createStatement()) {
                try { statement.setQueryTimeout(QUERY_TIMEOUT_SEC); } catch (Exception ignored) { }
                statement.setMaxRows(MAX_ROWS + 1);
                boolean hasResult = statement.execute(stmt);
                if (hasResult) {
                    try (ResultSet rs = statement.getResultSet()) {
                        ResultSetMetaData md = rs.getMetaData();
                        for (int i = 1; i <= md.getColumnCount(); i++) columns.add(md.getColumnLabel(i));
                        while (rs.next()) {
                            if (rows.size() >= MAX_ROWS) {
                                truncated = true;
                                break;
                            }
                            List<Object> row = new ArrayList<>(md.getColumnCount());
                            for (int i = 1; i <= md.getColumnCount(); i++) row.add(cell(rs, i));
                            rows.add(row);
                        }
                    }
                } else {
                    affectedRows = Math.max(0, statement.getUpdateCount());
                }
                c.commit();
            } catch (Exception error) {
                try { c.rollback(); } catch (Exception ignored) { }
                throw error;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad(kind + " failed: " + rootMessage(e));
        }

        audit.log("system", "DATA_EXPLORER_SQL_EXECUTE",
                "ds=" + dataSourceId + " kind=" + kind + " statementHash=" + statementHash(stmt)
                        + " affected=" + affectedRows);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statementType", kind);
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        out.put("affectedRows", affectedRows);
        out.put("truncated", truncated);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    public Map<String, Object> readTable(TableReadRequest request) {
        TableRef ref = resolve(request == null ? null : request.dataSourceId(),
                request == null ? null : request.schema(), request == null ? null : request.table());
        int limit = request.limit() == null ? TABLE_PAGE_SIZE : request.limit();
        int offset = request.offset() == null ? 0 : request.offset();
        if (limit < 1 || limit > TABLE_PAGE_SIZE_MAX) {
            throw ApiException.bad("Page size must be between 1 and " + TABLE_PAGE_SIZE_MAX);
        }
        if (offset < 0 || offset > TABLE_OFFSET_MAX) {
            throw ApiException.bad("Offset must be between 0 and " + TABLE_OFFSET_MAX);
        }

        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> keys;
        try (Connection c = connections.openPooled(ref.dataSource())) {
            keys = primaryKeys(c, ref);
            String orderBy = keys.isEmpty() ? "" : " ORDER BY " + joinQuoted(c, keys);
            String sql = "SELECT * FROM " + qualified(c, ref) + orderBy;
            try { c.setReadOnly(true); } catch (Exception ignored) { }
            try (Statement st = c.createStatement()) {
                st.setMaxRows(offset + limit + 1);
                try { st.setQueryTimeout(QUERY_TIMEOUT_SEC); } catch (Exception ignored) { }
                try (ResultSet rs = st.executeQuery(sql)) {
                    ResultSetMetaData md = rs.getMetaData();
                    int skipped = 0;
                    while (skipped < offset && rs.next()) skipped++;
                    while (rows.size() <= limit && rs.next()) {
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= md.getColumnCount(); i++) {
                            row.put(md.getColumnLabel(i), cell(rs, i));
                        }
                        rows.add(row);
                    }
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad("Failed reading " + ref.displayName() + ": " + rootMessage(e));
        }

        boolean hasMore = rows.size() > limit;
        if (hasMore) rows.remove(rows.size() - 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataSourceId", ref.dataSource().getId());
        out.put("schema", ref.schema());
        out.put("table", ref.table());
        out.put("columns", ref.columns());
        out.put("primaryKeys", keys);
        out.put("editable", !keys.isEmpty());
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        out.put("offset", offset);
        out.put("limit", limit);
        out.put("hasMore", hasMore);
        out.put("elapsedMs", System.currentTimeMillis() - start);
        return out;
    }

    public Map<String, Object> insert(TableInsertRequest request) {
        TableRef ref = resolve(request == null ? null : request.dataSourceId(),
                request == null ? null : request.schema(), request == null ? null : request.table());
        Map<String, Object> values = normalizedValues(ref, request.values());
        if (values.isEmpty()) throw ApiException.bad("Provide at least one value to insert");

        return write(ref, "INSERT", c -> {
            List<String> columns = new ArrayList<>(values.keySet());
            String sql = "INSERT INTO " + qualified(c, ref) + " (" + joinQuoted(c, columns) + ") VALUES ("
                    + String.join(", ", Collections.nCopies(columns.size(), "?")) + ")";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, 1, columns, values, ref);
                int affected = ps.executeUpdate();
                if (affected != 1) {
                    throw ApiException.conflict("Insert affected " + affected + " rows; expected exactly one");
                }
                return mutationResult("INSERT", affected, ref);
            }
        });
    }

    public Map<String, Object> update(TableUpdateRequest request) {
        TableRef ref = resolve(request == null ? null : request.dataSourceId(),
                request == null ? null : request.schema(), request == null ? null : request.table());
        Map<String, Object> values = normalizedValues(ref, request.values());
        if (values.isEmpty()) throw ApiException.bad("Provide at least one changed value");

        return write(ref, "UPDATE", c -> {
            List<String> keys = requireKeys(c, ref, request.keyValues());
            Set<String> keySet = lowerSet(keys);
            values.keySet().removeIf(column -> keySet.contains(column.toLowerCase(Locale.ROOT)));
            if (values.isEmpty()) throw ApiException.bad("Primary-key values cannot be changed in Data Explorer");
            List<String> columns = new ArrayList<>(values.keySet());
            String assignments = comparisons(c, columns, ", ");
            String where = comparisons(c, keys, " AND ");
            String sql = "UPDATE " + qualified(c, ref) + " SET " + assignments + " WHERE " + where;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                int index = bind(ps, 1, columns, values, ref);
                bind(ps, index, keys, request.keyValues(), ref);
                int affected = ps.executeUpdate();
                requireSingleMutation("Update", affected);
                return mutationResult("UPDATE", affected, ref);
            }
        });
    }

    public Map<String, Object> delete(TableDeleteRequest request) {
        TableRef ref = resolve(request == null ? null : request.dataSourceId(),
                request == null ? null : request.schema(), request == null ? null : request.table());
        return write(ref, "DELETE", c -> {
            List<String> keys = requireKeys(c, ref, request.keyValues());
            String where = comparisons(c, keys, " AND ");
            String sql = "DELETE FROM " + qualified(c, ref) + " WHERE " + where;
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                bind(ps, 1, keys, request.keyValues(), ref);
                int affected = ps.executeUpdate();
                requireSingleMutation("Delete", affected);
                return mutationResult("DELETE", affected, ref);
            }
        });
    }

    private TableRef resolve(Long dataSourceId, String schema, String table) {
        if (dataSourceId == null) throw ApiException.bad("dataSourceId is required");
        if (table == null || table.isBlank()) throw ApiException.bad("table is required");
        Map<String, Object> resolved = dataSources.resolveMetadataReference(dataSourceId, schema, table, null);
        String physicalSchema = text(resolved.get("schema"));
        String physicalTable = text(resolved.get("table"));
        List<Map<String, Object>> columns = dataSources.columns(dataSourceId, physicalSchema, physicalTable);
        if (columns.isEmpty()) throw ApiException.bad("Table has no visible columns");
        return new TableRef(dataSources.get(dataSourceId), physicalSchema, physicalTable, columns);
    }

    private Map<String, Object> normalizedValues(TableRef ref, Map<String, Object> submitted) {
        if (submitted == null) return new LinkedHashMap<>();
        Map<String, String> physical = new LinkedHashMap<>();
        for (Map<String, Object> column : ref.columns()) {
            String name = text(column.get("column"));
            boolean generated = Boolean.TRUE.equals(column.get("generated"))
                    || Boolean.TRUE.equals(column.get("autoIncrement"));
            if (!generated) physical.put(name.toLowerCase(Locale.ROOT), name);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        submitted.forEach((name, value) -> {
            String actual = physical.get(String.valueOf(name).toLowerCase(Locale.ROOT));
            if (actual == null) throw ApiException.bad("Column \"" + name + "\" is unknown, generated, or read-only");
            out.put(actual, value);
        });
        return out;
    }

    private List<String> requireKeys(Connection c, TableRef ref, Map<String, Object> submitted) throws SQLException {
        List<String> keys = primaryKeys(c, ref);
        if (keys.isEmpty()) {
            throw ApiException.conflict("Update and delete require a database primary key on " + ref.displayName());
        }
        if (submitted == null) throw ApiException.bad("Primary-key values are required");
        Map<String, Object> folded = fold(submitted);
        for (String key : keys) {
            if (!folded.containsKey(key.toLowerCase(Locale.ROOT))
                    || folded.get(key.toLowerCase(Locale.ROOT)) == null) {
                throw ApiException.bad("Primary-key value is required for " + key);
            }
        }
        return keys;
    }

    private Map<String, Object> write(TableRef ref, String action, SqlWrite operation) {
        try (Connection c = connections.open(ref.dataSource())) {
            c.setAutoCommit(false);
            try {
                Map<String, Object> result = operation.execute(c);
                c.commit();
                audit.log("system", "DATA_EXPLORER_" + action,
                        "ds=" + ref.dataSource().getId() + " table=" + ref.displayName());
                return result;
            } catch (Exception e) {
                try { c.rollback(); } catch (Exception ignored) { }
                if (e instanceof ApiException api) throw api;
                throw e;
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw ApiException.bad(action + " failed for " + ref.displayName() + ": " + rootMessage(e));
        }
    }

    private static Map<String, Object> mutationResult(String action, int affected, TableRef ref) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("action", action);
        result.put("affectedRows", affected);
        result.put("schema", ref.schema());
        result.put("table", ref.table());
        return result;
    }

    private static void requireSingleMutation(String action, int affected) {
        if (affected == 0) {
            throw ApiException.conflict(action + " found no row. It may have changed since it was loaded.");
        }
        if (affected != 1) {
            throw ApiException.conflict(action + " affected " + affected + " rows; transaction was rolled back");
        }
    }

    private static int bind(PreparedStatement ps, int start, List<String> columns,
                            Map<String, Object> values, TableRef ref) throws SQLException {
        Map<String, Object> folded = fold(values);
        int index = start;
        for (String column : columns) {
            Map<String, Object> metadata = ref.column(column);
            Object value = folded.get(column.toLowerCase(Locale.ROOT));
            int jdbcType = metadata.get("jdbcType") instanceof Number n ? n.intValue() : Types.OTHER;
            if (value == null) ps.setNull(index++, jdbcType);
            else ps.setObject(index++, jdbcValue(value, jdbcType), jdbcType);
        }
        return index;
    }

    private static Object jdbcValue(Object value, int jdbcType) {
        if (!(value instanceof String text) || text.isBlank()) return value;
        try {
            return switch (jdbcType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                        Types.NUMERIC, Types.DECIMAL, Types.FLOAT, Types.REAL, Types.DOUBLE ->
                        new java.math.BigDecimal(text.trim());
                case Types.BOOLEAN, Types.BIT -> booleanValue(text);
                case Types.DATE -> java.sql.Date.valueOf(text.trim());
                case Types.TIME -> Time.valueOf(text.trim());
                case Types.TIME_WITH_TIMEZONE -> java.time.OffsetTime.parse(text.trim());
                case Types.TIMESTAMP -> Timestamp.valueOf(text.trim().replace('T', ' '));
                case Types.TIMESTAMP_WITH_TIMEZONE -> OffsetDateTime.parse(text.trim());
                default -> text;
            };
        } catch (RuntimeException e) {
            String type;
            try { type = JDBCType.valueOf(jdbcType).getName(); } catch (Exception ignored) { type = String.valueOf(jdbcType); }
            throw ApiException.bad("Value \"" + text + "\" is not valid for " + type);
        }
    }

    private static boolean booleanValue(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(normalized) || "1".equals(normalized)) return true;
        if ("false".equals(normalized) || "0".equals(normalized)) return false;
        throw new IllegalArgumentException("Expected true, false, 1, or 0");
    }

    private static List<String> primaryKeys(Connection c, TableRef ref) throws SQLException {
        boolean mysql = SqlDialect.of(ref.dataSource()) == SqlDialect.MYSQL;
        String catalog = mysql ? (ref.schema() == null ? c.getCatalog() : ref.schema()) : null;
        String schemaPattern = mysql ? null : ref.schema();
        Map<Short, String> ordered = new TreeMap<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(catalog, schemaPattern, ref.table())) {
            while (rs.next()) ordered.put(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME"));
        }
        return new ArrayList<>(ordered.values());
    }

    private static String qualified(Connection c, TableRef ref) throws SQLException {
        return ref.schema() == null || ref.schema().isBlank()
                ? quote(c, ref.table())
                : quote(c, ref.schema()) + "." + quote(c, ref.table());
    }

    private static String joinQuoted(Connection c, List<String> identifiers) throws SQLException {
        List<String> quoted = new ArrayList<>();
        for (String identifier : identifiers) quoted.add(quote(c, identifier));
        return String.join(", ", quoted);
    }

    private static String comparisons(Connection c, List<String> identifiers, String delimiter) throws SQLException {
        List<String> expressions = new ArrayList<>();
        for (String identifier : identifiers) expressions.add(quote(c, identifier) + " = ?");
        return String.join(delimiter, expressions);
    }

    private static String quote(Connection c, String identifier) throws SQLException {
        String q = c.getMetaData().getIdentifierQuoteString();
        if (q == null || q.isBlank()) q = "\"";
        return q + identifier.replace(q, q + q) + q;
    }

    private static Map<String, Object> fold(Map<String, Object> values) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (values != null) values.forEach((key, value) -> out.put(key.toLowerCase(Locale.ROOT), value));
        return out;
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> out = new HashSet<>();
        values.forEach(value -> out.add(value.toLowerCase(Locale.ROOT)));
        return out;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        return current.getMessage() == null ? error.getMessage() : current.getMessage();
    }

    private static String statementKind(String statement) {
        Matcher matcher = FIRST_KEYWORD.matcher(statement);
        if (!matcher.find()) throw ApiException.bad("Could not identify the SQL statement type");
        return matcher.group(1).toUpperCase(Locale.ROOT);
    }

    /**
     * Accept one statement while allowing semicolons inside quoted values and comments.
     * A trailing delimiter is optional; any non-whitespace after it is rejected.
     */
    private static String singleStatement(String sql) {
        String text = sql == null ? "" : sql.trim();
        boolean ended = false;
        boolean lineComment = false;
        boolean blockComment = false;
        char quote = 0;
        int statementEnd = -1;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (lineComment) {
                if (current == '\n' || current == '\r') lineComment = false;
                continue;
            }
            if (blockComment) {
                if (current == '*' && next == '/') {
                    blockComment = false;
                    i++;
                }
                continue;
            }
            if (quote != 0) {
                if (current == quote) {
                    if (next == quote) i++;
                    else quote = 0;
                }
                continue;
            }
            if (current == '-' && next == '-') {
                lineComment = true;
                i++;
            } else if (current == '/' && next == '*') {
                blockComment = true;
                i++;
            } else if (current == '\'' || current == '"') {
                quote = current;
            } else if (current == ';') {
                if (ended) throw ApiException.bad("Only one SQL statement can be executed at a time");
                ended = true;
                statementEnd = i;
            } else if (ended && !Character.isWhitespace(current)) {
                throw ApiException.bad("Only one SQL statement can be executed at a time");
            }
        }
        if (quote != 0 || blockComment) throw ApiException.bad("SQL contains an unterminated quote or comment");
        String statement = statementEnd >= 0 ? text.substring(0, statementEnd).trim() : text;
        if (statement.isBlank()) throw ApiException.bad("A SQL statement is required");
        return statement;
    }

    private static String statementHash(String statement) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(statement.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (Exception e) {
            return "unavailable";
        }
    }

    private static Object cell(ResultSet rs, int i) throws SQLException {
        Object value = rs.getObject(i);
        if (value == null) return null;
        if (value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Blob blob) return "[binary " + blob.length() + " bytes]";
        if (value instanceof Clob clob) {
            int length = (int) Math.min(clob.length(), CELL_CAP);
            String content = clob.getSubString(1, length);
            return clob.length() > CELL_CAP ? content + "..." : content;
        }
        String string = String.valueOf(value);
        return string.length() > CELL_CAP ? string.substring(0, CELL_CAP) + "..." : string;
    }

    private record TableRef(DataSourceEntity dataSource, String schema, String table,
                            List<Map<String, Object>> columns) {
        String displayName() {
            return (schema == null || schema.isBlank() ? "" : schema + ".") + table;
        }

        Map<String, Object> column(String name) {
            return columns.stream()
                    .filter(column -> name.equalsIgnoreCase(text(column.get("column"))))
                    .findFirst()
                    .orElseThrow(() -> ApiException.bad("Unknown column " + name));
        }
    }

    @FunctionalInterface
    private interface SqlWrite {
        Map<String, Object> execute(Connection connection) throws Exception;
    }
}
