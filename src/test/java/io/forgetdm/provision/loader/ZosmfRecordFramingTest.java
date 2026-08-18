package io.forgetdm.provision.loader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ZosmfRecordFramingTest {
    @TempDir Path temp;

    @Test
    void emitsBigEndianLengthsAndPreservesUtf8LogicalRecords() throws Exception {
        Path input = temp.resolve("rows.tsv");
        Files.writeString(input, "1\tAlice\r\n2\tBjörk\n", StandardCharsets.UTF_8);

        try (DataInputStream framed = new DataInputStream(
                new ZosmfJobClient.RecordFramingInputStream(input, Db2ZosLoadJclBuilder.MAX_RECORD_BYTES))) {
            byte[] first = framed.readNBytes(framed.readInt());
            byte[] second = framed.readNBytes(framed.readInt());
            assertEquals("1\tAlice", new String(first, StandardCharsets.UTF_8));
            assertEquals("2\tBjörk", new String(second, StandardCharsets.UTF_8));
            assertEquals(-1, framed.read());
        }
    }
}
