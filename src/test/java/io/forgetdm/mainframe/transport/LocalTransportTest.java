package io.forgetdm.mainframe.transport;

import io.forgetdm.common.ApiException;
import io.forgetdm.mainframe.MainframeConnectionEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.*;

class LocalTransportTest {

    @TempDir
    Path directory;

    @Test
    void publishReplacesDataAndMetadataOnlyForExpectedVersion() throws Exception {
        LocalTransport transport = new LocalTransport();
        MainframeConnectionEntity connection = connection();
        Path target = directory.resolve("CUSTOMER.DATA");
        Files.write(target, new byte[]{1, 2, 3});
        MainframeTransport.ResourceVersion expected = transport.version(connection, "CUSTOMER.DATA");
        Path staged = directory.resolve("generated.bin");
        Files.write(staged, new byte[]{7, 8, 9, 10});

        MainframeTransport.PublishReceipt receipt = transport.publish(
                connection, "CUSTOMER.DATA", staged, "FB", 2, expected);

        assertArrayEquals(new byte[]{7, 8, 9, 10}, Files.readAllBytes(target));
        MainframeTransport.RemoteFile stat = transport.stat(connection, "CUSTOMER.DATA");
        assertEquals("FB", stat.recfm());
        assertEquals(2, stat.lrecl());
        assertEquals(4L, stat.sizeBytes());
        assertTrue(receipt.version().exists());
        assertNotEquals(expected.value(), receipt.version().value());
        assertFalse(Files.exists(directory.resolve(receipt.stagingName())));
    }

    @Test
    void staleTargetVersionIsRejectedWithoutChangingTarget() throws Exception {
        LocalTransport transport = new LocalTransport();
        MainframeConnectionEntity connection = connection();
        Path target = directory.resolve("CUSTOMER.DATA");
        Files.write(target, new byte[]{1, 2, 3});
        MainframeTransport.ResourceVersion stale = transport.version(connection, "CUSTOMER.DATA");

        Files.write(target, new byte[]{4, 5, 6, 7});
        Files.setLastModifiedTime(target, FileTime.fromMillis(System.currentTimeMillis() + 2_000));
        Path staged = directory.resolve("generated.bin");
        Files.write(staged, new byte[]{9, 9});

        ApiException error = assertThrows(ApiException.class, () -> transport.publish(
                connection, "CUSTOMER.DATA", staged, "FB", 2, stale));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertArrayEquals(new byte[]{4, 5, 6, 7}, Files.readAllBytes(target));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().contains("forgetdm-stage")));
        }
    }

    @Test
    void traversalNamesAreRejected() {
        LocalTransport transport = new LocalTransport();
        ApiException error = assertThrows(ApiException.class,
                () -> transport.version(connection(), "../outside.data"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    private MainframeConnectionEntity connection() {
        MainframeConnectionEntity connection = new MainframeConnectionEntity();
        connection.setName("local-test");
        connection.setType("LOCAL");
        connection.setBaseDir(directory.toString());
        return connection;
    }
}
