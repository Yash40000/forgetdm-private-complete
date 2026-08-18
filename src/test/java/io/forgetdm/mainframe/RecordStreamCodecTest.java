package io.forgetdm.mainframe;

import io.forgetdm.common.ApiException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class RecordStreamCodecTest {

    @Test
    void fixedRecordsRoundTripOneRecordAtATime() throws Exception {
        byte[] image = new byte[]{1, 2, 3, 4, 5, 6};
        RecordStreamCodec.Reader reader = RecordStreamCodec.reader(new ByteArrayInputStream(image), "FB", 3);

        assertArrayEquals(new byte[]{1, 2, 3}, reader.next());
        assertArrayEquals(new byte[]{4, 5, 6}, reader.next());
        assertNull(reader.next());
    }

    @Test
    void fixedReaderRejectsTrailingPartialRecord() throws Exception {
        RecordStreamCodec.Reader reader = RecordStreamCodec.reader(
                new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), "FB", 3);

        assertArrayEquals(new byte[]{1, 2, 3}, reader.next());
        ApiException error = assertThrows(ApiException.class, reader::next);
        assertTrue(error.getMessage().contains("Truncated record"));
    }

    @Test
    void variableRecordsRoundTripWithCanonicalRdw() throws Exception {
        ByteArrayOutputStream image = new ByteArrayOutputStream();
        RecordStreamCodec.Writer writer = RecordStreamCodec.writer(image, "VB");
        writer.write(new byte[]{10, 11});
        writer.write(new byte[]{20, 21, 22});

        assertArrayEquals(new byte[]{0, 6, 0, 0, 10, 11, 0, 7, 0, 0, 20, 21, 22}, image.toByteArray());

        RecordStreamCodec.Reader reader = RecordStreamCodec.reader(
                new ByteArrayInputStream(image.toByteArray()), "VB", 10);
        assertArrayEquals(new byte[]{10, 11}, reader.next());
        assertArrayEquals(new byte[]{20, 21, 22}, reader.next());
        assertNull(reader.next());
    }

    @Test
    void variableReaderRejectsInvalidOversizedAndTruncatedRdw() {
        ApiException invalid = assertThrows(ApiException.class, () ->
                RecordStreamCodec.reader(new ByteArrayInputStream(new byte[]{0, 3, 0, 0}), "VB", 10).next());
        assertTrue(invalid.getMessage().contains("Invalid RDW"));

        ApiException oversized = assertThrows(ApiException.class, () ->
                RecordStreamCodec.reader(new ByteArrayInputStream(new byte[]{0, 12, 0, 0}), "VB", 10).next());
        assertTrue(oversized.getMessage().contains("exceeds LRECL"));

        ApiException truncated = assertThrows(ApiException.class, () ->
                RecordStreamCodec.reader(new ByteArrayInputStream(new byte[]{0, 7, 0, 0, 1}), "VB", 10).next());
        assertTrue(truncated.getMessage().contains("Truncated VB record"));
    }
}
