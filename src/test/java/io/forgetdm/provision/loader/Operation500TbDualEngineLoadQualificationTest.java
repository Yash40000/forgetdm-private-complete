package io.forgetdm.provision.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Real bounded native loads plus exact 500 TB planning for PostgreSQL and Oracle. */
class Operation500TbDualEngineLoadQualificationTest {
    private static final BigInteger TOTAL_BYTES = new BigInteger("500000000000000");
    private static final BigInteger CHUNK_BYTES = BigInteger.valueOf(256L << 20);

    @Test
    void qualifiesPostgresCopyAndOracleDirectPathAgainstOneManifest() throws Exception {
        String pgUrl = env("FORGETDM_LOAD_PG_URL");
        String oraUrl = env("FORGETDM_LOAD_ORACLE_URL");
        Assumptions.assumeTrue(pgUrl != null && oraUrl != null, "Set dual-engine load environment variables");
        int rows = Integer.parseInt(System.getenv().getOrDefault("FORGETDM_LOAD_SAMPLE_ROWS", "100000"));
        String token = Long.toUnsignedString(System.nanoTime(), 36).toUpperCase();
        String pgTable = "op500_load_" + token.toLowerCase();
        String oraTable = "OP500_LOAD_" + token;
        Path evidence = Path.of(System.getProperty("forgetdm.load.evidence.dir", "target/operation-500tb-load"));
        Files.createDirectories(evidence);
        Path data = evidence.resolve("dual-engine-sample.tsv");
        try (var writer = Files.newBufferedWriter(data, StandardCharsets.UTF_8)) {
            for (int i = 1; i <= rows; i++) writer.write(i + "\tCUSTOMER-" + i + "\t" + (i * 17L) + "\n");
        }

        DataSourceEntity pg = source("POSTGRES", pgUrl, env("FORGETDM_LOAD_PG_USER"), env("FORGETDM_LOAD_PG_PASS"));
        DataSourceEntity ora = source("ORACLE", oraUrl, env("FORGETDM_LOAD_ORACLE_USER"), env("FORGETDM_LOAD_ORACLE_PASS"));
        try {
            execute(pg, "CREATE TABLE public." + pgTable + " (id bigint PRIMARY KEY, customer_ref varchar(80), amount bigint)");
            execute(ora, "CREATE TABLE " + oraTable + " (id NUMBER(19) PRIMARY KEY, customer_ref VARCHAR2(80), amount NUMBER(19))");

            NativeLoadRequest pgRequest = new NativeLoadRequest(pg, "public", pgTable,
                    List.of("id", "customer_ref", "amount"), data, "\t", false, "INSERT", Map.of());
            NativeLoadRequest oraRequest = new NativeLoadRequest(ora, env("FORGETDM_LOAD_ORACLE_USER"), oraTable,
                    List.of("ID", "CUSTOMER_REF", "AMOUNT"), data, "\t", false, "INSERT",
                    Map.of("jdbcTypes", "-5,12,-5", "rowsExpected", String.valueOf(rows),
                            "batchSize", "50000", "oracleLoadProfile", "DIRECT_RECOVERABLE",
                            "jobId", "OPERATION-500TB"));

            Instant pgStart = Instant.now();
            NativeLoadResult pgResult = new PostgresCopyLoadExecutor().execute(pgRequest);
            long pgMillis = Duration.between(pgStart, Instant.now()).toMillis();
            assertTrue(pgResult.success(), pgResult.message());

            Instant oraStart = Instant.now();
            NativeLoadResult oraResult = new OracleSqlLoaderExecutor().execute(oraRequest);
            long oraMillis = Duration.between(oraStart, Instant.now()).toMillis();
            assertTrue(oraResult.success(), oraResult.message() + " " + oraResult.stderr());

            long expectedSum = (long) rows * (rows + 1L) / 2L;
            Map<String, Long> pgProof = proof(pg, "public", pgTable);
            Map<String, Long> oraProof = proof(ora, env("FORGETDM_LOAD_ORACLE_USER"), oraTable);
            assertEquals(rows, pgProof.get("rows"));
            assertEquals(rows, oraProof.get("rows"));
            assertEquals(expectedSum, pgProof.get("idSum"));
            assertEquals(pgProof, oraProof);

            long chunks = ceil(TOTAL_BYTES, CHUNK_BYTES).longValueExact();
            LinkedHashMap<String, Object> report = new LinkedHashMap<>();
            report.put("operation", "OPERATION_500TB_DUAL_ENGINE_LOAD");
            report.put("generatedAt", Instant.now().toString());
            report.put("verdict", "REAL_BOUNDED_NATIVE_LOAD_PASS_500TB_PHYSICAL_PENDING");
            report.put("logicalBytesPerEngine", TOTAL_BYTES.toString());
            report.put("restartChunkBytes", CHUNK_BYTES.toString());
            report.put("restartChunksPerEngine", chunks);
            report.put("sampleRowsPerEngine", rows);
            report.put("sampleFileBytes", Files.size(data));
            report.put("postgres", Map.of("strategy", pgResult.strategy(), "nativeUsed", pgResult.nativeUsed(),
                    "elapsedMillis", pgMillis, "proof", pgProof));
            report.put("oracle", Map.of("strategy", oraResult.strategy(), "nativeUsed", oraResult.nativeUsed(),
                    "elapsedMillis", oraMillis, "proof", oraProof));
            report.put("claimBoundary", "Real native loaders were measured on a bounded sample; 500 TB was planned, not physically stored or loaded on this workstation.");
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(evidence.resolve("operation-500tb-dual-load.json").toFile(), report);
            Files.writeString(evidence.resolve("operation-500tb-dual-load.md"), """
                    # Operation 500 TB dual-engine load

                    **Verdict:** REAL BOUNDED NATIVE LOAD PASS; 500 TB PHYSICAL CERTIFICATION PENDING

                    | Engine | Physical path | Sample rows | Result |
                    | --- | --- | ---: | --- |
                    | PostgreSQL | COPY FROM STDIN | %,d | PASS |
                    | Oracle | SQL*Loader direct recoverable | %,d | PASS |

                    Both targets reconciled row count, ID sum and amount sum. The exact 500 TB plan uses %,d
                    restart chunks of 256 MiB per engine. This is not evidence that 500 TB was physically loaded.
                    """.formatted(rows, rows, chunks), StandardCharsets.UTF_8);
        } finally {
            try { execute(pg, "DROP TABLE IF EXISTS public." + pgTable); } catch (Exception ignored) {}
            try { execute(ora, "DROP TABLE " + oraTable + " PURGE"); } catch (Exception ignored) {}
        }
    }

    private static Map<String, Long> proof(DataSourceEntity ds, String schema, String table) throws Exception {
        try (var c = DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword());
             Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*), SUM(id), SUM(amount) FROM " + schema + "." + table)) {
            rs.next();
            return Map.of("rows", rs.getLong(1), "idSum", rs.getLong(2), "amountSum", rs.getLong(3));
        }
    }

    private static void execute(DataSourceEntity ds, String sql) throws Exception {
        try (var c = DriverManager.getConnection(ds.getJdbcUrl(), ds.getUsername(), ds.getPassword()); Statement st = c.createStatement()) { st.execute(sql); }
    }

    private static DataSourceEntity source(String kind, String url, String user, String pass) {
        DataSourceEntity ds = new DataSourceEntity(); ds.setKind(kind); ds.setJdbcUrl(url); ds.setUsername(user); ds.setPassword(pass); ds.setRole("TARGET"); return ds;
    }

    private static String env(String name) { String value = System.getenv(name); return value == null || value.isBlank() ? null : value; }
    private static BigInteger ceil(BigInteger a, BigInteger b) { return a.add(b).subtract(BigInteger.ONE).divide(b); }
}
