package io.forgetdm.mainframe;

import io.forgetdm.common.ApiException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** One-record-at-a-time FB/VB reader and writer with strict boundary validation. */
final class RecordStreamCodec {
    private RecordStreamCodec() { }

    static Reader reader(InputStream input, String recfm, int lrecl) {
        return new Reader(input, RecordSplitter.isVariable(recfm), lrecl);
    }

    static Writer writer(OutputStream output, String recfm) {
        return new Writer(output, RecordSplitter.isVariable(recfm));
    }

    static final class Reader {
        private final InputStream input;
        private final boolean variable;
        private final int lrecl;
        private long offset;

        private Reader(InputStream input, boolean variable, int lrecl) {
            if (!variable && lrecl <= 0) throw ApiException.bad("FB record format requires a positive LRECL");
            this.input = input;
            this.variable = variable;
            this.lrecl = lrecl;
        }

        byte[] next() throws IOException {
            if (!variable) return fixed();
            byte[] rdw = firstOrEof(4);
            if (rdw == null) return null;
            int length = ((rdw[0] & 0xff) << 8) | (rdw[1] & 0xff);
            if (length < 4) throw ApiException.bad("Invalid RDW length " + length + " at offset " + (offset - 4));
            int dataLength = length - 4;
            if (lrecl > 0 && length > lrecl) {
                throw ApiException.bad("VB record length " + length + " exceeds LRECL " + lrecl
                        + " at offset " + (offset - 4));
            }
            return exact(dataLength, "Truncated VB record at offset " + (offset - 4));
        }

        private byte[] fixed() throws IOException {
            byte[] first = firstOrEof(lrecl);
            if (first == null) return null;
            return first;
        }

        private byte[] firstOrEof(int count) throws IOException {
            int first = input.read();
            if (first < 0) return null;
            byte[] result = new byte[count];
            result[0] = (byte) first;
            offset++;
            int read = readFully(result, 1, count - 1);
            if (read != count - 1) {
                throw ApiException.bad("Truncated record at byte offset " + (offset - 1));
            }
            return result;
        }

        private byte[] exact(int count, String message) throws IOException {
            byte[] result = new byte[count];
            if (readFully(result, 0, count) != count) throw ApiException.bad(message);
            return result;
        }

        private int readFully(byte[] target, int start, int length) throws IOException {
            int total = 0;
            while (total < length) {
                int read = input.read(target, start + total, length - total);
                if (read < 0) break;
                if (read == 0) continue;
                total += read;
                offset += read;
            }
            return total;
        }
    }

    static final class Writer {
        private final OutputStream output;
        private final boolean variable;

        private Writer(OutputStream output, boolean variable) {
            this.output = output;
            this.variable = variable;
        }

        void write(byte[] record) throws IOException {
            if (variable) {
                int length = record.length + 4;
                if (length > 65_535) throw ApiException.bad("VB record exceeds the two-byte RDW limit");
                output.write((length >>> 8) & 0xff);
                output.write(length & 0xff);
                output.write(0);
                output.write(0);
            }
            output.write(record);
        }
    }
}
