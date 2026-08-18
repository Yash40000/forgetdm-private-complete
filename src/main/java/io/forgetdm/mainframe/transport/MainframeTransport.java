package io.forgetdm.mainframe.transport;

import io.forgetdm.common.ApiException;
import io.forgetdm.mainframe.MainframeConnectionEntity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Versioned, bounded-memory transport for mainframe data sets and local test fixtures. */
public interface MainframeTransport {

    record RemoteFile(String name, String recfm, Integer lrecl, Long sizeBytes, String dsorg) { }

    record ResourceVersion(boolean exists, String value) {
        public static ResourceVersion missing() { return new ResourceVersion(false, null); }
    }

    record ReadHandle(InputStream stream, ResourceVersion version, Long sizeBytes) implements AutoCloseable {
        @Override public void close() throws IOException { stream.close(); }
    }

    record PublishReceipt(String targetName, ResourceVersion version, String stagingName) { }

    List<RemoteFile> list(MainframeConnectionEntity conn, String pattern);

    RemoteFile stat(MainframeConnectionEntity conn, String name);

    /** Open an untranslated binary stream and capture the source version used by this run. */
    ReadHandle openRead(MainframeConnectionEntity conn, String name);

    /** Current resource version, or {@link ResourceVersion#missing()} when it does not exist. */
    ResourceVersion version(MainframeConnectionEntity conn, String name);

    default void assertVersion(MainframeConnectionEntity conn, String name, ResourceVersion expected) {
        ResourceVersion current = version(conn, name);
        if (expected == null || current.exists() != expected.exists()
                || (expected.exists() && !Objects.equals(current.value(), expected.value()))) {
            throw ApiException.conflict("Mainframe resource changed during masking: " + name);
        }
    }

    /** Publish a local staged image using optimistic concurrency and adapter-specific atomic replacement. */
    PublishReceipt publish(MainframeConnectionEntity conn, String name, Path stagedData,
                           String recfm, Integer lrecl, ResourceVersion expectedTarget);

    /** Compatibility helper for bounded fixtures and older callers. */
    default byte[] fetch(MainframeConnectionEntity conn, String name) {
        try (ReadHandle handle = openRead(conn, name)) {
            return handle.stream().readAllBytes();
        } catch (IOException e) {
            throw ApiException.bad("Fetch failed for " + name + ": " + e.getMessage());
        }
    }

    /** Compatibility helper. Production masking calls {@link #publish}. */
    default void put(MainframeConnectionEntity conn, String name, byte[] data, String recfm, Integer lrecl) {
        Path staged = null;
        try {
            staged = Files.createTempFile("forgetdm-mainframe-put-", ".bin");
            Files.write(staged, data);
            publish(conn, name, staged, recfm, lrecl, version(conn, name));
        } catch (IOException e) {
            throw ApiException.bad("Write failed for " + name + ": " + e.getMessage());
        } finally {
            if (staged != null) try { Files.deleteIfExists(staged); } catch (IOException ignored) { }
        }
    }
}
