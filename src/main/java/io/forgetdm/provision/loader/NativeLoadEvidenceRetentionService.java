package io.forgetdm.provision.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/** Applies the deployment evidence-retention policy while preserving directories placed on legal hold. */
@Service
public class NativeLoadEvidenceRetentionService {
    static final String RETENTION_ENV = "FORGETDM_NATIVE_LOAD_EVIDENCE_RETENTION_DAYS";
    private static final Logger log = LoggerFactory.getLogger(NativeLoadEvidenceRetentionService.class);

    @Scheduled(fixedDelayString = "${forgetdm.native-evidence-purge-interval-ms:86400000}")
    public void purgeExpiredEvidence() {
        int removed = purgeExpired(evidenceRoot(), retentionDays(), Instant.now());
        if (removed > 0) log.info("Purged {} expired native-loader evidence package(s)", removed);
    }

    static int purgeExpired(Path root, int retentionDays, Instant now) {
        if (root == null || retentionDays <= 0 || now == null || !Files.isDirectory(root)) return 0;
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        int removed = 0;
        try (Stream<Path> children = Files.list(root)) {
            for (Path directory : children.filter(Files::isDirectory).toList()) {
                if (!Files.isRegularFile(directory.resolve("manifest.properties"))) continue;
                if (Files.exists(directory.resolve(".hold"))) continue;
                Instant modified;
                try { modified = Files.getLastModifiedTime(directory).toInstant(); }
                catch (Exception ignored) { continue; }
                if (!modified.isBefore(cutoff)) continue;
                deleteTree(directory);
                if (!Files.exists(directory)) removed++;
            }
        } catch (Exception e) {
            log.warn("Could not apply native-loader evidence retention under {}: {}", root, e.getMessage());
        }
        return removed;
    }

    static int retentionDays() {
        try {
            String value = System.getenv(RETENTION_ENV);
            return value == null || value.isBlank() ? 365 : Math.max(0, Integer.parseInt(value.trim()));
        } catch (Exception ignored) { return 365; }
    }

    static Path evidenceRoot() {
        String configured = System.getenv(OracleSqlLoaderExecutor.EVIDENCE_DIR_ENV);
        return configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".forgetdm", "evidence", "native-loader")
                : Path.of(configured);
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                try { Files.deleteIfExists(path); } catch (Exception ignored) { }
            }
        } catch (Exception ignored) { }
    }
}
