package io.forgetdm.provision.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Time-bounded rolling physical qualification with retained ten-minute measurements. */
class Operation500TbRollingEnduranceTest {
    @Test
    void rollsIdenticalBankingBatchesThroughPostgresAndOracle() throws Exception {
        String pgUrl = env("FORGETDM_LOAD_PG_URL");
        String oraUrl = env("FORGETDM_LOAD_ORACLE_URL");
        Assumptions.assumeTrue(pgUrl != null && oraUrl != null, "Dual-engine environment is required");
        int durationMinutes = integer("forgetdm.endurance.minutes", 90);
        int intervalMinutes = integer("forgetdm.endurance.intervalMinutes", 10);
        int batchRows = integer("forgetdm.endurance.batchRows", 100_000);
        long durationNanos = Duration.ofMinutes(durationMinutes).toNanos();
        long intervalNanos = Duration.ofMinutes(intervalMinutes).toNanos();
        Path evidence = Path.of(System.getProperty("forgetdm.endurance.evidence.dir", "target/operation-500tb-endurance"));
        Files.createDirectories(evidence);
        Path data = evidence.resolve("rolling-banking-batch.tsv");
        String token = Long.toUnsignedString(System.nanoTime(), 36).toUpperCase();
        String pgTable = "op500_roll_" + token.toLowerCase();
        String oraTable = "OP500_ROLL_" + token;
        DataSourceEntity pg = source("POSTGRES", pgUrl, env("FORGETDM_LOAD_PG_USER"), env("FORGETDM_LOAD_PG_PASS"));
        DataSourceEntity ora = source("ORACLE", oraUrl, env("FORGETDM_LOAD_ORACLE_USER"), env("FORGETDM_LOAD_ORACLE_PASS"));
        List<Map<String, Object>> windows = new ArrayList<>();
        Instant startedAt = Instant.now();
        long started = System.nanoTime();
        long nextWindow = started + intervalNanos;
        long windowRows = 0, windowBytes = 0, totalRows = 0, totalBytes = 0, nextId = 1;
        long windowGenerateMs = 0, windowPgMs = 0, windowOracleMs = 0;
        long totalFailures = 0;
        long peakHeap = 0, batches = 0;
        try {
            execute(pg, "CREATE TABLE public." + pgTable + " (id bigint PRIMARY KEY, customer_ref varchar(80), account_no varchar(32), amount bigint, narrative text)");
            execute(ora, "CREATE TABLE " + oraTable + " (id NUMBER(19) PRIMARY KEY, customer_ref VARCHAR2(80), account_no VARCHAR2(32), amount NUMBER(19), narrative VARCHAR2(240))");
            while (System.nanoTime() - started < durationNanos) {
                long batchStartId = nextId;
                long t = System.nanoTime();
                try (var writer = Files.newBufferedWriter(data, StandardCharsets.UTF_8)) {
                    for (int i = 0; i < batchRows; i++, nextId++) {
                        writer.write(nextId + "\tCUST-" + nextId + "\t" + String.format("%016d", nextId)
                                + "\t" + (nextId * 17L) + "\tBanking transaction " + nextId + "\n");
                    }
                }
                windowGenerateMs += millis(t);
                long bytes = Files.size(data);
                NativeLoadRequest pgRequest = request(pg, "public", pgTable, data, batchRows, false);
                NativeLoadRequest oraRequest = request(ora, env("FORGETDM_LOAD_ORACLE_USER"), oraTable, data, batchRows, true);
                t = System.nanoTime();
                NativeLoadResult pgResult = new PostgresCopyLoadExecutor().execute(pgRequest);
                windowPgMs += millis(t);
                assertTrue(pgResult.success(), pgResult.message());
                t = System.nanoTime();
                NativeLoadResult oraResult = new OracleSqlLoaderExecutor().execute(oraRequest);
                windowOracleMs += millis(t);
                assertTrue(oraResult.success(), oraResult.message() + " " + oraResult.stderr());
                batches++;
                windowRows += batchRows;
                totalRows += batchRows;
                windowBytes += bytes;
                totalBytes += bytes;
                peakHeap = Math.max(peakHeap, ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());

                long now = System.nanoTime();
                if (now >= nextWindow || now - started >= durationNanos) {
                    long reconcileStarted = System.nanoTime();
                    Map<String, Long> pgProof = proof(pg, "public", pgTable);
                    Map<String, Long> oraProof = proof(ora, env("FORGETDM_LOAD_ORACLE_USER"), oraTable);
                    assertEquals(windowRows, pgProof.get("rows"));
                    assertEquals(pgProof, oraProof);
                    long reconcileMillis = millis(reconcileStarted);
                    LinkedHashMap<String, Object> window = new LinkedHashMap<>();
                    window.put("window", windows.size() + 1);
                    window.put("elapsedMinutes", round((now - started) / 60_000_000_000.0));
                    window.put("rowsPerEngine", windowRows);
                    window.put("rowsAcrossBothEngines", windowRows * 2);
                    window.put("stagedBytes", windowBytes);
                    window.put("generationMillis", windowGenerateMs);
                    window.put("postgresLoadMillis", windowPgMs);
                    window.put("oracleLoadMillis", windowOracleMs);
                    window.put("reconciliationMillis", reconcileMillis);
                    window.put("rowsPerSecondAcrossBothEngines", Math.round(windowRows * 2.0 / Math.max(0.001, (now - (nextWindow - intervalNanos)) / 1_000_000_000.0)));
                    window.put("reconciliation", pgProof);
                    window.put("peakHeapBytes", peakHeap);
                    window.put("failures", totalFailures);
                    window.put("status", "PASS");
                    windows.add(window);
                    writeReports(evidence, startedAt, durationMinutes, intervalMinutes, batchRows, batches,
                            totalRows, totalBytes, peakHeap, totalFailures, windows, "RUNNING");
                    long recycleStarted = System.nanoTime();
                    execute(pg, "TRUNCATE TABLE public." + pgTable);
                    execute(ora, "TRUNCATE TABLE " + oraTable);
                    window.put("recycleMillis", millis(recycleStarted));
                    writeReports(evidence, startedAt, durationMinutes, intervalMinutes, batchRows, batches,
                            totalRows, totalBytes, peakHeap, totalFailures, windows, "RUNNING");
                    windowRows = windowBytes = windowGenerateMs = windowPgMs = windowOracleMs = 0;
                    nextWindow = now + intervalNanos;
                }
            }
            writeReports(evidence, startedAt, durationMinutes, intervalMinutes, batchRows, batches,
                    totalRows, totalBytes, peakHeap, totalFailures, windows, "PASS");
        } finally {
            try { execute(pg, "DROP TABLE IF EXISTS public." + pgTable); } catch (Exception ignored) {}
            try { execute(ora, "DROP TABLE " + oraTable + " PURGE"); } catch (Exception ignored) {}
            Files.deleteIfExists(data);
        }
    }

    private static NativeLoadRequest request(DataSourceEntity ds, String schema, String table, Path data, int rows, boolean oracle) {
        Map<String, String> options = oracle ? Map.of("jdbcTypes", "-5,12,12,-5,12", "rowsExpected", String.valueOf(rows),
                "batchSize", "50000", "oracleLoadProfile", "DIRECT_RECOVERABLE", "jobId", "OPERATION-500TB-ENDURANCE") : Map.of();
        List<String> columns = oracle
                ? List.of("ID", "CUSTOMER_REF", "ACCOUNT_NO", "AMOUNT", "NARRATIVE")
                : List.of("id", "customer_ref", "account_no", "amount", "narrative");
        return new NativeLoadRequest(ds, schema, table, columns, data, "\t", false, "INSERT", options);
    }

    private static void writeReports(Path dir, Instant started, int duration, int interval, int batchRows,
                                     long batches, long rows, long bytes, long heap, long failures,
                                     List<Map<String, Object>> windows,
                                     String status) throws Exception {
        LinkedHashMap<String, Object> report = new LinkedHashMap<>();
        report.put("operation", "OPERATION_500TB_ROLLING_ENDURANCE"); report.put("status", status);
        report.put("startedAt", started.toString()); report.put("updatedAt", Instant.now().toString());
        report.put("plannedMinutes", duration); report.put("reportIntervalMinutes", interval);
        report.put("batchRows", batchRows); report.put("completedBatches", batches);
        report.put("rowsPerEngine", rows); report.put("rowsAcrossBothEngines", rows * 2);
        report.put("stagedBytesGenerated", bytes); report.put("peakHeapBytes", heap);
        report.put("failures", failures); report.put("windows", windows);
        report.put("claimBoundary", "Cumulative rolling physical load; not a simultaneously resident 500 TB database.");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve("operation-500tb-rolling-endurance.json").toFile(), report);
        StringBuilder md = new StringBuilder("# Operation 500 TB rolling endurance\n\n**Status:** ").append(status)
                .append("\n\n| Window | Elapsed min | Rows/engine | Rows both | Staged bytes | PostgreSQL ms | Oracle ms | Reconcile ms | Recycle ms | Rows/s both | Peak heap | Failures | Status |\n")
                .append("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |\n");
        for (Map<String, Object> w : windows) md.append("| ").append(w.get("window")).append(" | ").append(w.get("elapsedMinutes"))
                .append(" | ").append(w.get("rowsPerEngine")).append(" | ").append(w.get("rowsAcrossBothEngines"))
                .append(" | ").append(w.get("stagedBytes")).append(" | ").append(w.get("postgresLoadMillis"))
                .append(" | ").append(w.get("oracleLoadMillis")).append(" | ").append(w.get("reconciliationMillis"))
                .append(" | ").append(w.get("recycleMillis")).append(" | ").append(w.get("rowsPerSecondAcrossBothEngines"))
                .append(" | ").append(w.get("peakHeapBytes")).append(" | ").append(w.get("failures"))
                .append(" | ").append(w.get("status")).append(" |\n");
        md.append("\nCumulative rows per engine: ").append(rows).append("; across both engines: ").append(rows * 2)
                .append(". This is rolling-load evidence, not a resident 500 TB certification.\n");
        Files.writeString(dir.resolve("operation-500tb-rolling-endurance.md"), md, StandardCharsets.UTF_8);
    }

    private static Map<String, Long> proof(DataSourceEntity ds, String schema, String table) throws Exception {
        try (var c = DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword()); Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(id), SUM(amount) FROM " + schema + "." + table)) {
            rs.next(); return Map.of("rows", rs.getLong(1), "idSum", rs.getLong(2), "amountSum", rs.getLong(3));
        }
    }
    private static void execute(DataSourceEntity ds, String sql) throws Exception { try (var c=DriverManager.getConnection(ds.getJdbcUrl(),ds.getUsername(),ds.getPassword()); Statement st=c.createStatement()){st.execute(sql);} }
    private static DataSourceEntity source(String kind,String url,String user,String pass){DataSourceEntity ds=new DataSourceEntity();ds.setKind(kind);ds.setJdbcUrl(url);ds.setUsername(user);ds.setPassword(pass);ds.setRole("TARGET");return ds;}
    private static String env(String n){String v=System.getenv(n);return v==null||v.isBlank()?null:v;}
    private static int integer(String n,int d){try{return Integer.parseInt(System.getProperty(n,String.valueOf(d)));}catch(Exception e){return d;}}
    private static long millis(long n){return Math.max(0,(System.nanoTime()-n)/1_000_000);}
    private static double round(double n){return Math.round(n*100.0)/100.0;}
}
