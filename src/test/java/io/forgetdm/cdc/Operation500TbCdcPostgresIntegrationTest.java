package io.forgetdm.cdc;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.forgetdm.datasource.DataSourceEntity;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Disposable integration proof against a real PostgreSQL logical replication stream. */
class Operation500TbCdcPostgresIntegrationTest {
    @Test
    void capturesRealWalInBoundedPolls() throws Exception {
        String url = System.getenv("FORGETDM_CDC_TEST_URL");
        Assumptions.assumeTrue(url != null && !url.isBlank(), "Set FORGETDM_CDC_TEST_URL to run real CDC evidence");
        String user = System.getenv().getOrDefault("FORGETDM_CDC_TEST_USER", "forgetdm");
        String pass = System.getenv().getOrDefault("FORGETDM_CDC_TEST_PASS", "forgetdm");
        String suffix = Long.toUnsignedString(System.nanoTime(), 36).toLowerCase(Locale.ROOT);
        String table = "op500_cdc_" + suffix;
        String slot = "op500_cdc_" + suffix;

        DataSourceEntity ds = new DataSourceEntity();
        ds.setName("Operation 500 TB CDC PostgreSQL");
        ds.setKind("POSTGRES");
        ds.setRole("SOURCE");
        ds.setJdbcUrl(url);
        ds.setUsername(user);
        ds.setPassword(pass);

        PostgresCdcProvider provider = new PostgresCdcProvider();
        CdcCaptureEntity capture = new CdcCaptureEntity();
        capture.setSlotName(slot);
        List<CdcProvider.DecodedChange> changes = new ArrayList<>();
        try {
            CdcProvider.Preflight preflight = provider.preflight(ds);
            assertTrue(preflight.ok(), preflight.messages().toString());
            execute(url, user, pass, "CREATE TABLE public." + table + " (id bigint PRIMARY KEY, payload text NOT NULL)");
            CdcProvider.SlotInfo info = provider.createSlot(ds, slot);
            capture.setConfirmedLsn(info.confirmedLsn());

            try (var connection = DriverManager.getConnection(url, user, pass); Statement st = connection.createStatement()) {
                st.executeUpdate("INSERT INTO public." + table + " SELECT g, 'value-' || g FROM generate_series(1,1000) g");
                st.executeUpdate("UPDATE public." + table + " SET payload='changed-' || id WHERE id <= 100");
                st.executeUpdate("DELETE FROM public." + table + " WHERE id > 975");
            }

            int polls = 0;
            int largestPoll = 0;
            for (; polls < 30 && changes.size() < 1125; polls++) {
                CdcProvider.PollResult result = provider.poll(ds, capture, 200, 4_000);
                largestPoll = Math.max(largestPoll, result.changes().size());
                changes.addAll(result.changes().stream().filter(c -> table.equals(c.table)).toList());
                capture.setConfirmedLsn(result.confirmedLsn());
                if (result.reachedEnd() && changes.size() >= 1125) break;
            }
            Map<String, Long> counts = changes.stream().collect(java.util.stream.Collectors.groupingBy(c -> c.op, LinkedHashMap::new, java.util.stream.Collectors.counting()));
            assertEquals(1000L, counts.getOrDefault("I", 0L));
            assertEquals(100L, counts.getOrDefault("U", 0L));
            assertEquals(25L, counts.getOrDefault("D", 0L));
            assertFalse(capture.getConfirmedLsn() == null || capture.getConfirmedLsn().isBlank());
            assertTrue(changes.stream().allMatch(c -> c.pk.containsKey("id")));

            Path dir = Path.of(System.getProperty("forgetdm.cdc.evidence.dir", "target/operation-500tb-cdc"));
            Files.createDirectories(dir);
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("generatedAt", Instant.now().toString());
            evidence.put("engine", "PostgreSQL");
            evidence.put("mechanism", provider.mechanism());
            evidence.put("status", "PASS");
            evidence.put("realDatabase", true);
            evidence.put("capturedChanges", changes.size());
            evidence.put("operations", counts);
            evidence.put("maxChangesPerPoll", 200);
            evidence.put("largestObservedPoll", largestPoll);
            evidence.put("transactionAtomicity", "A complete source transaction may exceed maxChanges; checkpoints never split a transaction.");
            evidence.put("confirmedLsn", capture.getConfirmedLsn());
            evidence.put("lagBytes", provider.lag(ds, capture.getConfirmedLsn()));
            evidence.put("scaleClaim", "bounded real-WAL functional proof; not a physical 5 TB delta throughput test");
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve("postgres-real-cdc-report.json").toFile(), evidence);
            Files.writeString(dir.resolve("postgres-real-cdc-report.md"),
                    "# Real PostgreSQL CDC evidence\n\n**PASS** - captured 1,000 inserts, 100 updates, and 25 deletes from WAL in bounded polls.\n",
                    StandardCharsets.UTF_8);
        } finally {
            try { provider.dropSlot(ds, slot); } catch (Exception ignored) {}
            try { execute(url, user, pass, "DROP TABLE IF EXISTS public." + table); } catch (Exception ignored) {}
        }
    }

    private static void execute(String url, String user, String pass, String sql) throws Exception {
        try (var c = DriverManager.getConnection(url, user, pass); Statement st = c.createStatement()) { st.execute(sql); }
    }
}
