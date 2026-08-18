package io.forgetdm.topology;

import io.forgetdm.datasource.DataSourceEntity;
import io.forgetdm.datasource.DataSourceService;
import io.forgetdm.datasource.SqlDialect;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads one schema in bulk. Tables and columns are always fetched with schema-wide metadata calls.
 * Supported engines also use one set-oriented query for PK/UK/FK evidence; only an unknown driver
 * falls back to per-table JDBC key calls, and that fallback is named in the capture evidence.
 */
@Component
public class TopologyMetadataReader {

    public Capture read(Connection connection, DataSourceEntity source, String requestedSchema,
                        Cancellation cancellation, Progress progress) throws SQLException {
        String schema = DataSourceService.normalizeSchema(connection, requestedSchema);
        SqlDialect dialect = SqlDialect.of(source);
        String catalog = dialect == SqlDialect.MYSQL ? (schema == null ? connection.getCatalog() : schema) : null;
        String schemaPattern = dialect == SqlDialect.MYSQL ? null : schema;

        cancellation.check();
        List<TableDef> tables = readTables(connection, catalog, schemaPattern, schema, cancellation);
        progress.tablesDiscovered(tables.size());
        cancellation.check();

        Map<String, List<ColumnDef>> columns = readColumns(
                connection, catalog, schemaPattern, schema, cancellation, progress);
        ConstraintCapture constraints;
        String providerMode;
        try {
            constraints = readBulkConstraints(connection, dialect, schema, cancellation);
            providerMode = "BULK_" + dialect.name();
        } catch (SQLException unsupported) {
            constraints = readJdbcConstraints(connection, catalog, schemaPattern, tables, cancellation, progress);
            providerMode = "JDBC_METADATA_FALLBACK";
        }

        Set<String> primary = constraints.primaryColumns();
        Set<String> unique = constraints.uniqueColumns();
        Map<String, List<ColumnDef>> marked = new LinkedHashMap<>();
        for (Map.Entry<String, List<ColumnDef>> entry : columns.entrySet()) {
            List<ColumnDef> next = entry.getValue().stream()
                    .map(column -> {
                        String key = columnKey(entry.getKey(), column.name());
                        return column.withKeys(primary.contains(key), unique.contains(key));
                    })
                    .toList();
            marked.put(entry.getKey(), next);
        }

        List<TableDef> populated = tables.stream()
                .map(table -> table.withColumnCount(marked.getOrDefault(tableKey(table.name()), List.of()).size()))
                .sorted(Comparator.comparing(TableDef::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new Capture(schema, providerMode, populated, marked, constraints.foreignKeys());
    }

    private List<TableDef> readTables(Connection connection, String catalog, String schemaPattern,
                                      String resolvedSchema, Cancellation cancellation) throws SQLException {
        List<TableDef> tables = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getTables(
                catalog, schemaPattern, "%", new String[]{"TABLE", "VIEW", "MATERIALIZED VIEW"})) {
            int seen = 0;
            while (rs.next()) {
                if ((seen++ & 127) == 0) cancellation.check();
                String name = rs.getString("TABLE_NAME");
                if (SqlDialect.isSystemTable(name)) continue;
                String physicalSchema = firstText(rs.getString("TABLE_SCHEM"), resolvedSchema);
                String type = firstText(rs.getString("TABLE_TYPE"), "TABLE");
                tables.add(new TableDef(rs.getString("TABLE_CAT"), physicalSchema, name, type, 0));
            }
        }
        return tables;
    }

    private Map<String, List<ColumnDef>> readColumns(Connection connection, String catalog, String schemaPattern,
                                                      String resolvedSchema, Cancellation cancellation,
                                                      Progress progress) throws SQLException {
        Map<String, List<ColumnDef>> columns = new LinkedHashMap<>();
        try (ResultSet rs = connection.getMetaData().getColumns(catalog, schemaPattern, "%", "%")) {
            int seen = 0;
            while (rs.next()) {
                if ((seen++ & 255) == 0) cancellation.check();
                String table = rs.getString("TABLE_NAME");
                if (SqlDialect.isSystemTable(table)) continue;
                ColumnDef column = new ColumnDef(
                        rs.getString("COLUMN_NAME"),
                        rs.getInt("ORDINAL_POSITION"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("DATA_TYPE"),
                        longValue(rs, "COLUMN_SIZE"),
                        intValue(rs, "DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        false,
                        false,
                        "YES".equalsIgnoreCase(safeString(rs, "IS_GENERATEDCOLUMN"))
                                || "YES".equalsIgnoreCase(safeString(rs, "IS_AUTOINCREMENT")),
                        rs.getString("COLUMN_DEF"));
                columns.computeIfAbsent(tableKey(table), ignored -> new ArrayList<>()).add(column);
                if ((seen & 511) == 0) progress.columnsRead(seen);
            }
        }
        columns.values().forEach(list -> list.sort(Comparator.comparingInt(ColumnDef::ordinal)));
        progress.columnsRead(seenCount(columns));
        return columns;
    }

    private ConstraintCapture readBulkConstraints(Connection connection, SqlDialect dialect, String schema,
                                                   Cancellation cancellation) throws SQLException {
        return switch (dialect) {
            case POSTGRES, H2 -> informationSchemaConstraints(connection, schema, false, cancellation);
            case MYSQL -> informationSchemaConstraints(connection, schema, true, cancellation);
            case SQLSERVER -> sqlServerConstraints(connection, schema, cancellation);
            case ORACLE -> oracleConstraints(connection, schema, cancellation);
            case DB2 -> db2Constraints(connection, schema, cancellation);
            default -> throw new SQLException("No set-oriented constraint provider for " + dialect);
        };
    }

    private ConstraintCapture informationSchemaConstraints(Connection connection, String schema, boolean mysql,
                                                           Cancellation cancellation) throws SQLException {
        Set<String> primary = new LinkedHashSet<>();
        Set<String> unique = new LinkedHashSet<>();
        String keySql = """
                SELECT tc.table_name, kcu.column_name, tc.constraint_type
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                   AND kcu.table_schema = tc.table_schema
                 WHERE LOWER(tc.table_schema) = LOWER(?)
                   AND tc.constraint_type IN ('PRIMARY KEY', 'UNIQUE')
                """;
        try (PreparedStatement ps = connection.prepareStatement(keySql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cancellation.check();
                    String key = columnKey(rs.getString(1), rs.getString(2));
                    if ("PRIMARY KEY".equalsIgnoreCase(rs.getString(3))) primary.add(key);
                    unique.add(key);
                }
            }
        }

        String fkSql = mysql ? """
                SELECT kcu.constraint_name, kcu.table_name, kcu.column_name,
                       kcu.referenced_table_name, kcu.referenced_column_name, kcu.ordinal_position
                  FROM information_schema.key_column_usage kcu
                 WHERE LOWER(kcu.table_schema) = LOWER(?)
                   AND kcu.referenced_table_name IS NOT NULL
                 ORDER BY kcu.constraint_name, kcu.ordinal_position
                """ : """
                SELECT tc.constraint_name, kcu.table_name, kcu.column_name,
                       ccu.table_name, ccu.column_name, kcu.ordinal_position
                  FROM information_schema.table_constraints tc
                  JOIN information_schema.key_column_usage kcu
                    ON kcu.constraint_name = tc.constraint_name
                   AND kcu.table_schema = tc.table_schema
                  JOIN information_schema.referential_constraints rc
                    ON rc.constraint_name = tc.constraint_name
                   AND rc.constraint_schema = tc.table_schema
                  JOIN information_schema.key_column_usage ccu
                    ON ccu.constraint_name = rc.unique_constraint_name
                   AND ccu.constraint_schema = rc.unique_constraint_schema
                   AND ccu.ordinal_position = kcu.position_in_unique_constraint
                 WHERE LOWER(tc.table_schema) = LOWER(?)
                   AND tc.constraint_type = 'FOREIGN KEY'
                 ORDER BY tc.constraint_name, kcu.ordinal_position
                """;
        return new ConstraintCapture(primary, unique, readForeignKeys(connection, fkSql, schema, cancellation));
    }

    private ConstraintCapture sqlServerConstraints(Connection connection, String schema,
                                                   Cancellation cancellation) throws SQLException {
        Set<String> primary = new LinkedHashSet<>();
        Set<String> unique = new LinkedHashSet<>();
        String keySql = """
                SELECT t.name, c.name, i.is_primary_key, i.is_unique
                  FROM sys.tables t
                  JOIN sys.schemas s ON s.schema_id = t.schema_id
                  JOIN sys.indexes i ON i.object_id = t.object_id AND (i.is_primary_key = 1 OR i.is_unique = 1)
                  JOIN sys.index_columns ic ON ic.object_id = i.object_id AND ic.index_id = i.index_id
                  JOIN sys.columns c ON c.object_id = ic.object_id AND c.column_id = ic.column_id
                 WHERE s.name = ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(keySql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cancellation.check();
                    String key = columnKey(rs.getString(1), rs.getString(2));
                    if (rs.getBoolean(3)) primary.add(key);
                    if (rs.getBoolean(4) || rs.getBoolean(3)) unique.add(key);
                }
            }
        }
        String fkSql = """
                SELECT fk.name, ct.name, cc.name, pt.name, pc.name, fkc.constraint_column_id
                  FROM sys.foreign_keys fk
                  JOIN sys.foreign_key_columns fkc ON fkc.constraint_object_id = fk.object_id
                  JOIN sys.tables ct ON ct.object_id = fkc.parent_object_id
                  JOIN sys.schemas cs ON cs.schema_id = ct.schema_id
                  JOIN sys.columns cc ON cc.object_id = ct.object_id AND cc.column_id = fkc.parent_column_id
                  JOIN sys.tables pt ON pt.object_id = fkc.referenced_object_id
                  JOIN sys.columns pc ON pc.object_id = pt.object_id AND pc.column_id = fkc.referenced_column_id
                 WHERE cs.name = ?
                 ORDER BY fk.name, fkc.constraint_column_id
                """;
        return new ConstraintCapture(primary, unique, readForeignKeys(connection, fkSql, schema, cancellation));
    }

    private ConstraintCapture oracleConstraints(Connection connection, String schema,
                                                Cancellation cancellation) throws SQLException {
        Set<String> primary = new LinkedHashSet<>();
        Set<String> unique = new LinkedHashSet<>();
        String keySql = """
                SELECT c.table_name, cc.column_name, c.constraint_type
                  FROM all_constraints c
                  JOIN all_cons_columns cc
                    ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name
                 WHERE c.owner = UPPER(?) AND c.constraint_type IN ('P', 'U')
                """;
        try (PreparedStatement ps = connection.prepareStatement(keySql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cancellation.check();
                    String key = columnKey(rs.getString(1), rs.getString(2));
                    if ("P".equalsIgnoreCase(rs.getString(3))) primary.add(key);
                    unique.add(key);
                }
            }
        }
        String fkSql = """
                SELECT c.constraint_name, c.table_name, cc.column_name,
                       p.table_name, pc.column_name, cc.position
                  FROM all_constraints c
                  JOIN all_cons_columns cc
                    ON cc.owner = c.owner AND cc.constraint_name = c.constraint_name
                  JOIN all_constraints p
                    ON p.owner = c.r_owner AND p.constraint_name = c.r_constraint_name
                  JOIN all_cons_columns pc
                    ON pc.owner = p.owner AND pc.constraint_name = p.constraint_name AND pc.position = cc.position
                 WHERE c.owner = UPPER(?) AND c.constraint_type = 'R'
                 ORDER BY c.constraint_name, cc.position
                """;
        return new ConstraintCapture(primary, unique, readForeignKeys(connection, fkSql, schema, cancellation));
    }

    private ConstraintCapture db2Constraints(Connection connection, String schema,
                                             Cancellation cancellation) throws SQLException {
        Set<String> primary = new LinkedHashSet<>();
        Set<String> unique = new LinkedHashSet<>();
        String keySql = """
                SELECT k.tabname, k.colname, t.type
                  FROM syscat.tabconst t
                  JOIN syscat.keycoluse k
                    ON k.tabschema = t.tabschema AND k.tabname = t.tabname AND k.constname = t.constname
                 WHERE t.tabschema = UPPER(?) AND t.type IN ('P', 'U')
                """;
        try (PreparedStatement ps = connection.prepareStatement(keySql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cancellation.check();
                    String key = columnKey(rs.getString(1), rs.getString(2));
                    if ("P".equalsIgnoreCase(rs.getString(3))) primary.add(key);
                    unique.add(key);
                }
            }
        }
        String fkSql = """
                SELECT r.constname, fk.tabname, fk.colname, pk.tabname, pk.colname, fk.colseq
                  FROM syscat.references r
                  JOIN syscat.keycoluse fk
                    ON fk.tabschema = r.tabschema AND fk.tabname = r.tabname AND fk.constname = r.constname
                  JOIN syscat.keycoluse pk
                    ON pk.tabschema = r.reftabschema AND pk.tabname = r.reftabname
                   AND pk.constname = r.refkeyname AND pk.colseq = fk.colseq
                 WHERE r.tabschema = UPPER(?)
                 ORDER BY r.constname, fk.colseq
                """;
        return new ConstraintCapture(primary, unique, readForeignKeys(connection, fkSql, schema, cancellation));
    }

    private List<ForeignKeyDef> readForeignKeys(Connection connection, String sql, String schema,
                                                Cancellation cancellation) throws SQLException {
        Map<String, MutableForeignKey> grouped = new LinkedHashMap<>();
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, schema);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    cancellation.check();
                    String constraint = firstText(rs.getString(1), "FK");
                    String childTable = rs.getString(2);
                    String parentTable = rs.getString(4);
                    String groupKey = constraint.toLowerCase(Locale.ROOT) + "|" + tableKey(childTable)
                            + "|" + tableKey(parentTable);
                    MutableForeignKey fk = grouped.computeIfAbsent(groupKey,
                            ignored -> new MutableForeignKey(constraint, childTable, parentTable));
                    fk.childColumns.add(rs.getString(3));
                    fk.parentColumns.add(rs.getString(5));
                }
            }
        }
        return grouped.values().stream().map(MutableForeignKey::freeze).toList();
    }

    private ConstraintCapture readJdbcConstraints(Connection connection, String catalog, String schemaPattern,
                                                  List<TableDef> tables, Cancellation cancellation,
                                                  Progress progress) throws SQLException {
        Set<String> primary = new LinkedHashSet<>();
        Set<String> unique = new LinkedHashSet<>();
        List<ForeignKeyDef> foreignKeys = new ArrayList<>();
        DatabaseMetaData metadata = connection.getMetaData();
        int index = 0;
        for (TableDef table : tables) {
            cancellation.check();
            Map<String, MutableForeignKey> grouped = new LinkedHashMap<>();
            try (ResultSet rs = metadata.getPrimaryKeys(catalog, schemaPattern, table.name())) {
                while (rs.next()) {
                    String key = columnKey(table.name(), rs.getString("COLUMN_NAME"));
                    primary.add(key);
                    unique.add(key);
                }
            }
            try (ResultSet rs = metadata.getImportedKeys(catalog, schemaPattern, table.name())) {
                while (rs.next()) {
                    String constraint = firstText(rs.getString("FK_NAME"), "FK_" + table.name());
                    String parent = rs.getString("PKTABLE_NAME");
                    String groupKey = constraint.toLowerCase(Locale.ROOT) + "|" + tableKey(table.name())
                            + "|" + tableKey(parent);
                    MutableForeignKey fk = grouped.computeIfAbsent(groupKey,
                            ignored -> new MutableForeignKey(constraint, table.name(), parent));
                    fk.childColumns.add(rs.getString("FKCOLUMN_NAME"));
                    fk.parentColumns.add(rs.getString("PKCOLUMN_NAME"));
                }
            }
            grouped.values().stream().map(MutableForeignKey::freeze).forEach(foreignKeys::add);
            progress.keysRead(++index, tables.size());
        }
        return new ConstraintCapture(primary, unique, foreignKeys);
    }

    private static int seenCount(Map<String, List<ColumnDef>> columns) {
        return columns.values().stream().mapToInt(List::size).sum();
    }

    private static String tableKey(String table) {
        return table == null ? "" : table.toLowerCase(Locale.ROOT);
    }

    private static String columnKey(String table, String column) {
        return tableKey(table) + "." + (column == null ? "" : column.toLowerCase(Locale.ROOT));
    }

    private static String safeString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static Long longValue(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer intValue(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String firstText(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    @FunctionalInterface
    public interface Cancellation {
        void check();
    }

    public interface Progress {
        default void tablesDiscovered(int count) {}
        default void columnsRead(int count) {}
        default void keysRead(int completed, int total) {}
    }

    public record Capture(String schema, String providerMode, List<TableDef> tables,
                          Map<String, List<ColumnDef>> columns, List<ForeignKeyDef> foreignKeys) {}

    public record TableDef(String catalog, String schema, String name, String type, int columnCount) {
        TableDef withColumnCount(int count) {
            return new TableDef(catalog, schema, name, type, count);
        }
    }

    public record ColumnDef(String name, int ordinal, String typeName, int jdbcType, Long length,
                            Integer scale, boolean nullable, boolean primaryKey, boolean uniqueKey,
                            boolean generated, String defaultExpression) {
        ColumnDef withKeys(boolean primary, boolean unique) {
            return new ColumnDef(name, ordinal, typeName, jdbcType, length, scale, nullable,
                    primary, unique, generated, defaultExpression);
        }
    }

    public record ForeignKeyDef(String name, String childTable, List<String> childColumns,
                                String parentTable, List<String> parentColumns) {}

    private record ConstraintCapture(Set<String> primaryColumns, Set<String> uniqueColumns,
                                     List<ForeignKeyDef> foreignKeys) {}

    private static final class MutableForeignKey {
        private final String name;
        private final String childTable;
        private final String parentTable;
        private final List<String> childColumns = new ArrayList<>();
        private final List<String> parentColumns = new ArrayList<>();

        private MutableForeignKey(String name, String childTable, String parentTable) {
            this.name = name;
            this.childTable = childTable;
            this.parentTable = parentTable;
        }

        private ForeignKeyDef freeze() {
            return new ForeignKeyDef(name, childTable, List.copyOf(childColumns),
                    parentTable, List.copyOf(parentColumns));
        }
    }
}
