package io.forgetdm.virtualization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Destructive only inside a uniquely named child dataset under FORGETDM_ZFS_POOL.
 * No Docker or H2 is used. Without a real OpenZFS host the test records BLOCKED evidence and is skipped.
 */
class Operation500TbCowQualificationTest {
    private static final BigInteger TOTAL_BYTES = new BigInteger("500000000000000");
    private static final String REPORT_JSON = "operation-500tb-cow-report.json";
    private static final String REPORT_MD = "operation-500tb-cow-report.md";

    @Test
    void qualifiesRealZfsCopyOnWriteWithoutDockerOrRecordsBlockedEvidence() throws Exception {
        Settings settings = Settings.load();
        Path evidenceDir = evidenceDirectory();
        Files.createDirectories(evidenceDir);

        if (!settings.configured()) {
            Map<String, Object> blocked = blockedEvidence(settings,
                    "FORGETDM_ZFS_HOST is not configured; this Windows host has no local OpenZFS engine.");
            writeEvidence(evidenceDir, blocked);
            Assumptions.assumeTrue(false, String.valueOf(blocked.get("reason")));
        }

        String token = "op500tb-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        RemoteResult result;
        try {
            result = runRemote(settings, token);
        } catch (Exception failure) {
            Map<String, Object> blocked = blockedEvidence(settings, rootMessage(failure));
            writeEvidence(evidenceDir, blocked);
            throw failure;
        }

        assertEquals(TOTAL_BYTES.toString(), result.value("logicalBytes"));
        assertEquals(result.value("sourceHashBefore"), result.value("cloneHashBefore"));
        assertEquals(result.value("sourceHashBefore"), result.value("sourceHashAfter"));
        assertNotEquals(result.value("sourceHashAfter"), result.value("cloneHashAfter"));
        assertTrue(result.longValue("cloneUsedAfter") > result.longValue("cloneUsedBefore"),
                "Changed clone blocks must consume additional physical space");
        assertTrue(result.longValue("snapshotMillis") >= 0);
        assertTrue(result.longValue("cloneMillis") >= 0);

        writeEvidence(evidenceDir, passEvidence(settings, result));
    }

    private static RemoteResult runRemote(Settings settings, String token) throws Exception {
        String command = settings.useSudo()
                ? "sudo -n bash -s -- " + shell(settings.pool()) + " " + shell(token) + " " + settings.sampleMiB()
                : "bash -s -- " + shell(settings.pool()) + " " + shell(token) + " " + settings.sampleMiB();
        ProcessBuilder builder = new ProcessBuilder("ssh", "-o", "BatchMode=yes", "-o",
                "StrictHostKeyChecking=accept-new", "-p", Integer.toString(settings.port()),
                settings.user() + "@" + settings.host(), command);
        Process process = builder.start();
        process.getOutputStream().write(remoteScript().getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        Thread out = new Thread(() -> transfer(process.getInputStream(), stdout), "cow-qualification-stdout");
        Thread err = new Thread(() -> transfer(process.getErrorStream(), stderr), "cow-qualification-stderr");
        out.start();
        err.start();
        if (!process.waitFor(20, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("Real ZFS COW qualification timed out after 20 minutes");
        }
        out.join(5_000);
        err.join(5_000);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Remote ZFS qualification failed (exit " + process.exitValue()
                    + "): " + tail(stderr.toString(StandardCharsets.UTF_8)));
        }
        return RemoteResult.parse(stdout.toString(StandardCharsets.UTF_8));
    }

    private static String remoteScript() {
        return """
                set -euo pipefail
                pool="$1"
                token="$2"
                sample_mib="$3"
                dataset="${pool}/qualification-${token}"
                clone="${pool}/qualification-clone-${token}"
                snap="${dataset}@baseline"
                cleanup() {
                  zfs destroy -r "$clone" >/dev/null 2>&1 || true
                  zfs destroy -r "$dataset" >/dev/null 2>&1 || true
                }
                trap cleanup EXIT
                command -v zfs >/dev/null
                zfs list -H -o name "$pool" >/dev/null
                zfs create "$dataset"
                zfs set compression=off "$dataset"
                mountpoint=$(zfs get -H -o value mountpoint "$dataset")
                payload="${mountpoint}/payload.bin"
                truncate -s 500000000000000 "$payload"
                dd if=/dev/urandom of="$payload" bs=1M count="$sample_mib" conv=notrunc status=none
                sync
                source_hash_before=$(dd if="$payload" bs=1M count="$sample_mib" status=none | sha256sum | awk '{print $1}')
                logical_bytes=$(stat -c %s "$payload")
                source_used=$(zfs get -Hp -o value used "$dataset")
                source_logical_used=$(zfs get -Hp -o value logicalused "$dataset")
                started=$(date +%s%N)
                zfs snapshot "$snap"
                snapshot_ms=$(( ($(date +%s%N) - started) / 1000000 ))
                started=$(date +%s%N)
                zfs clone "$snap" "$clone"
                clone_ms=$(( ($(date +%s%N) - started) / 1000000 ))
                clone_mount=$(zfs get -H -o value mountpoint "$clone")
                clone_payload="${clone_mount}/payload.bin"
                clone_hash_before=$(dd if="$clone_payload" bs=1M count="$sample_mib" status=none | sha256sum | awk '{print $1}')
                clone_used_before=$(zfs get -Hp -o value used "$clone")
                changed_mib=$(( sample_mib < 16 ? sample_mib : 16 ))
                dd if=/dev/urandom of="$clone_payload" bs=1M count="$changed_mib" conv=notrunc status=none
                sync
                clone_used_after=$(zfs get -Hp -o value used "$clone")
                clone_hash_after=$(dd if="$clone_payload" bs=1M count="$sample_mib" status=none | sha256sum | awk '{print $1}')
                source_hash_after=$(dd if="$payload" bs=1M count="$sample_mib" status=none | sha256sum | awk '{print $1}')
                printf 'logicalBytes=%s\n' "$logical_bytes"
                printf 'sampleMiB=%s\n' "$sample_mib"
                printf 'changedMiB=%s\n' "$changed_mib"
                printf 'sourceUsed=%s\n' "$source_used"
                printf 'sourceLogicalUsed=%s\n' "$source_logical_used"
                printf 'snapshotMillis=%s\n' "$snapshot_ms"
                printf 'cloneMillis=%s\n' "$clone_ms"
                printf 'cloneUsedBefore=%s\n' "$clone_used_before"
                printf 'cloneUsedAfter=%s\n' "$clone_used_after"
                printf 'sourceHashBefore=%s\n' "$source_hash_before"
                printf 'cloneHashBefore=%s\n' "$clone_hash_before"
                printf 'sourceHashAfter=%s\n' "$source_hash_after"
                printf 'cloneHashAfter=%s\n' "$clone_hash_after"
                """;
    }

    private static Map<String, Object> passEvidence(Settings settings, RemoteResult result) {
        LinkedHashMap<String, Object> root = baseEvidence(settings);
        root.put("verdict", "REAL_ZFS_COW_PASS_500TB_LOGICAL_NAMESPACE_PHYSICAL_SAMPLE_ONLY");
        root.put("realCowProven", true);
        root.put("dockerUsed", false);
        root.put("h2Used", false);
        root.put("physical500TbClaimAllowed", false);
        root.put("claimBoundary", "A real ZFS snapshot/clone and changed-block isolation were measured, but only the configured sample was physically populated.");
        root.put("measurements", result.values());
        root.put("timeModel", timeModel());
        return root;
    }

    private static Map<String, Object> blockedEvidence(Settings settings, String reason) {
        LinkedHashMap<String, Object> root = baseEvidence(settings);
        root.put("verdict", "REAL_COW_TEST_BLOCKED_NO_ZFS_ENGINE");
        root.put("realCowProven", false);
        root.put("dockerUsed", false);
        root.put("h2Used", false);
        root.put("physical500TbClaimAllowed", false);
        root.put("reason", reason);
        root.put("timeModel", timeModel());
        return root;
    }

    private static LinkedHashMap<String, Object> baseEvidence(Settings settings) {
        LinkedHashMap<String, Object> root = new LinkedHashMap<>();
        root.put("operation", "OPERATION_500TB_REAL_COW");
        root.put("generatedAt", Instant.now().toString());
        root.put("commit", System.getProperty("forgetdm.cow.commit", "UNRECORDED"));
        root.put("host", settings.host().isBlank() ? "NOT_CONFIGURED" : settings.host());
        root.put("pool", settings.pool());
        root.put("logicalBytes", TOTAL_BYTES.toString());
        root.put("sampleMiB", settings.sampleMiB());
        return root;
    }

    private static Map<String, Object> timeModel() {
        LinkedHashMap<String, Object> speeds = new LinkedHashMap<>();
        for (BigDecimal gbps : new BigDecimal[]{new BigDecimal("0.5"), BigDecimal.ONE,
                new BigDecimal("2"), new BigDecimal("5"), BigDecimal.TEN, new BigDecimal("20")}) {
            BigDecimal seconds = new BigDecimal(TOTAL_BYTES)
                    .divide(gbps.multiply(new BigDecimal("1000000000")), 6, RoundingMode.HALF_UP);
            speeds.put(gbps.stripTrailingZeros().toPlainString() + " GB/s", Map.of(
                    "hours", seconds.divide(new BigDecimal("3600"), 2, RoundingMode.HALF_UP),
                    "days", seconds.divide(new BigDecimal("86400"), 2, RoundingMode.HALF_UP)));
        }
        return Map.of(
                "initialBaselineRawTransfer", speeds,
                "cowSnapshot", "Normally seconds to a few minutes after the baseline is synchronized; dataset size is not recopied.",
                "rewind", "Normally seconds to minutes plus database crash recovery and validation.",
                "onePercentDeltaBytes", "5000000000000");
    }

    private static void writeEvidence(Path directory, Map<String, Object> evidence) throws Exception {
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(directory.resolve(REPORT_JSON).toFile(), evidence);
        String verdict = String.valueOf(evidence.get("verdict"));
        String markdown = "# Operation 500 TB real COW qualification\n\n"
                + "**Verdict:** `" + verdict + "`\n\n"
                + "This qualification never uses H2 or Docker. It requires an actual Linux OpenZFS host and proves "
                + "snapshot, clone, shared-block isolation, and changed-block allocation. A sparse 500 TB logical file "
                + "proves namespace scale; only the configured sample is physically written, so this is not a physical "
                + "500 TB throughput certification.\n\n"
                + "**Reason/claim boundary:** " + evidence.getOrDefault("reason", evidence.get("claimBoundary")) + "\n\n"
                + "## Raw-transfer planning range\n\n"
                + "| Sustained end-to-end rate | 500 TB raw transfer |\n| ---: | ---: |\n"
                + "| 0.5 GB/s | 11.57 days |\n| 1 GB/s | 5.79 days |\n| 2 GB/s | 2.89 days |\n"
                + "| 5 GB/s | 27.78 hours |\n| 10 GB/s | 13.89 hours |\n| 20 GB/s | 6.94 hours |\n\n"
                + "Production planning must add masking, reconciliation, source throttling, LOB behavior, and safety margin.\n";
        Files.writeString(directory.resolve(REPORT_MD), markdown, StandardCharsets.UTF_8);
    }

    private static Path evidenceDirectory() {
        return Path.of(System.getProperty("forgetdm.cow.evidenceDir",
                Path.of("target", "scale-evidence", "operation-500tb-cow").toString()));
    }

    private static void transfer(java.io.InputStream input, java.io.OutputStream output) {
        try (input; output) { input.transferTo(output); } catch (Exception ignored) { }
    }

    private static String shell(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String tail(String value) {
        String clean = value == null ? "" : value.trim();
        return clean.length() > 1_500 ? clean.substring(clean.length() - 1_500) : clean;
    }

    private static String rootMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }

    private record Settings(String host, String user, int port, String pool, boolean useSudo, int sampleMiB) {
        static Settings load() {
            return new Settings(value("FORGETDM_ZFS_HOST", ""), value("FORGETDM_ZFS_SSH_USER", "root"),
                    Integer.parseInt(value("FORGETDM_ZFS_SSH_PORT", "22")),
                    value("FORGETDM_ZFS_POOL", "tank/forgetdm"),
                    Boolean.parseBoolean(value("FORGETDM_ZFS_USE_SUDO", "false")),
                    Integer.getInteger("forgetdm.cow.sampleMiB", 256));
        }

        boolean configured() { return host != null && !host.isBlank(); }

        private static String value(String name, String fallback) {
            String system = System.getProperty(name);
            if (system != null && !system.isBlank()) return system.trim();
            String env = System.getenv(name);
            return env == null || env.isBlank() ? fallback : env.trim();
        }
    }

    private record RemoteResult(Map<String, String> values) {
        static RemoteResult parse(String output) {
            LinkedHashMap<String, String> values = new LinkedHashMap<>();
            for (String line : output.split("\\R")) {
                int separator = line.indexOf('=');
                if (separator > 0) values.put(line.substring(0, separator).trim(), line.substring(separator + 1).trim());
            }
            return new RemoteResult(values);
        }

        String value(String name) {
            String value = values.get(name);
            if (value == null) throw new IllegalStateException("Remote evidence is missing " + name);
            return value;
        }

        long longValue(String name) { return Long.parseLong(value(name)); }
    }
}
