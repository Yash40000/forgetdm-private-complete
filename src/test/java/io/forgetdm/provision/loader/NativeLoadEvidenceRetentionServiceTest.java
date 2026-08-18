package io.forgetdm.provision.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class NativeLoadEvidenceRetentionServiceTest {
    @TempDir Path root;

    @Test
    void purgesOnlyExpiredEvidenceThatIsNotOnHold() throws Exception {
        Instant now = Instant.parse("2026-08-02T12:00:00Z");
        Path expired = packageAt("job-1-table", now.minus(Duration.ofDays(400)));
        Path held = packageAt("job-2-table", now.minus(Duration.ofDays(500)));
        Files.writeString(held.resolve(".hold"), "investigation");
        Files.setLastModifiedTime(held, FileTime.from(now.minus(Duration.ofDays(500))));
        Path current = packageAt("job-3-table", now.minus(Duration.ofDays(20)));
        Path unrelated = Files.createDirectory(root.resolve("unrelated"));

        int removed = NativeLoadEvidenceRetentionService.purgeExpired(root, 365, now);

        assertEquals(1, removed);
        assertFalse(Files.exists(expired));
        assertTrue(Files.exists(held));
        assertTrue(Files.exists(current));
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void zeroRetentionDisablesPurge() throws Exception {
        Instant now = Instant.parse("2026-08-02T12:00:00Z");
        Path expired = packageAt("job-4-table", now.minus(Duration.ofDays(800)));

        assertEquals(0, NativeLoadEvidenceRetentionService.purgeExpired(root, 0, now));
        assertTrue(Files.exists(expired));
    }

    private Path packageAt(String name, Instant modified) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        Files.writeString(directory.resolve("manifest.properties"), "status=COMPLETED");
        Files.setLastModifiedTime(directory, FileTime.from(modified));
        return directory;
    }
}
