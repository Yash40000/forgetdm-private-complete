package io.forgetdm.provision.loader;

import io.forgetdm.datasource.DataSourceEntity;
import org.postgresql.PGConnection;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public class PostgresCopyLoadExecutor implements NativeLoadExecutor {
    @Override public String strategy() { return "POSTGRES_COPY"; }

    @Override
    public boolean supports(DataSourceEntity target) {
        String engine = NativeLoadRegistry.engineOf(target);
        return "POSTGRES".equals(engine) || "POSTGRESQL".equals(engine);
    }

    @Override
    public NativeLoadStrategy describe(DataSourceEntity target) {
        return new NativeLoadStrategy(
                "POSTGRES_COPY",
                NativeLoadRegistry.engineOf(target),
                true,
                getClass().getSimpleName(),
                "JDBC_MULTI_ROW",
                "IN_PROCESS_NATIVE_COPY",
                "",
                "In-process PostgreSQL COPY consumes bounded masked chunk files without buffering the load in heap.");
    }

    @Override
    public NativeLoadResult execute(NativeLoadRequest request) {
        Instant started = Instant.now();
        if (request == null || request.target() == null || request.dataFile() == null) {
            NativeLoadStrategy description = describe(request == null ? null : request.target());
            return NativeLoadSupport.skipped(strategy(), request == null ? null : request.target(),
                    "PostgreSQL COPY requires a target and a staged chunk file", description);
        }
        DataSourceEntity target = request.target();
        Properties properties = new Properties();
        if (target.getUsername() != null) properties.setProperty("user", target.getUsername());
        if (target.getPassword() != null) properties.setProperty("password", target.getPassword());
        try (Connection connection = DriverManager.getConnection(target.getJdbcUrl(), properties);
             Reader reader = Files.newBufferedReader(request.dataFile(), StandardCharsets.UTF_8)) {
            connection.setAutoCommit(false);
            String columns = request.columns().stream().map(NativeLoadSupport::qIdent)
                    .reduce((left, right) -> left + "," + right).orElse("");
            String delimiter = request.delimiter() == null || request.delimiter().isEmpty() ? "\t" : request.delimiter();
            if (delimiter.length() != 1) throw new NativeLoadException("PostgreSQL COPY delimiter must be one character");
            String copySql = "COPY " + NativeLoadSupport.qualified(request.schema(), request.table())
                    + " (" + columns + ") FROM STDIN WITH (FORMAT csv, DELIMITER "
                    + sqlLiteral(delimiter) + ", NULL '\\\\N', HEADER " + request.header() + ")";
            long rows = connection.unwrap(PGConnection.class).getCopyAPI().copyIn(copySql, reader);
            connection.commit();
            Instant finished = Instant.now();
            return new NativeLoadResult(strategy(), NativeLoadRegistry.engineOf(target), true, true, 0,
                    "COMPLETED", "PostgreSQL COPY committed " + rows + " row(s)", List.of(), List.of(),
                    List.of(request.dataFile()), "", "", started, finished,
                    Map.of("rowsLoaded", rows, "copySql", copySql, "dataFileBytes", Files.size(request.dataFile())));
        } catch (Exception e) {
            return new NativeLoadResult(strategy(), NativeLoadRegistry.engineOf(target), true, false, -1,
                    "FAILED", e.getMessage(), List.of(), List.of(), List.of(request.dataFile()), "", "", started,
                    Instant.now(), Map.of());
        }
    }

    private static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
