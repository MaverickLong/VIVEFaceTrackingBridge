package dev.maverick.ftbridge.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Internal protobuf codec for Companion command 192. */
final class VrConfigProtocol {

    private VrConfigProtocol() {}

    static byte[] request(int sequence, String key, String value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        integer(out, 1, sequence);
        integer(out, 2, value == null ? 0 : 1);
        string(out, 3, key);
        if (value != null) string(out, 4, value);
        return out.toByteArray();
    }

    static String response(byte[] bytes, int sequence, String expectedKey, boolean write) throws IOException {
        if (bytes == null || bytes.length > 128 * 1024) throw new IOException("Invalid Companion response");
        Reader in = new Reader(bytes);
        long seq = 0, error = 0, action = 0;
        String key = "", value = "";
        while (in.remaining() > 0) {
            long tag = in.varint();
            int field = (int) (tag >>> 3), wire = (int) (tag & 7);
            if (tag <= 0 || tag > 0xffffffffL || field == 0) throw new IOException("Invalid protobuf field");
            if (field >= 1 && field <= 3) {
                if (wire != 0) throw new IOException("Invalid response integer");
                long number = in.varint();
                if (field == 1) seq = number;
                if (field == 2) error = number;
                if (field == 3) action = number;
            } else if (field == 4 || field == 5) {
                if (wire != 2) throw new IOException("Invalid response string");
                String text = new String(in.bytes(), StandardCharsets.UTF_8);
                if (field == 4) key = text;
                else value = text;
            } else {
                in.skip(wire);
            }
        }
        if (seq != sequence || action != (write ? 1 : 0)) throw new IOException("Mismatched Companion response");
        if (error != 0) throw new IOException("VRConfig error " + error);
        if (!expectedKey.equals(key)) throw new IOException("Unexpected VRConfig key");
        return value;
    }

    private static void integer(ByteArrayOutputStream out, int field, int value) {
        varint(out, field << 3);
        varint(out, value);
    }

    private static void string(ByteArrayOutputStream out, int field, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        varint(out, (field << 3) | 2);
        varint(out, bytes.length);
        out.write(bytes, 0, bytes.length);
    }

    private static void varint(ByteArrayOutputStream out, int value) {
        while ((value & ~127) != 0) {
            out.write((value & 127) | 128);
            value >>>= 7;
        }
        out.write(value);
    }

    private static final class Reader {
        private final byte[] data;
        private int offset;

        Reader(byte[] data) { this.data = data; }
        int remaining() { return data.length - offset; }

        long varint() throws IOException {
            long result = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (remaining() == 0) throw new IOException("Truncated protobuf integer");
                int value = data[offset++] & 255;
                if (shift == 63 && (value & 254) != 0) throw new IOException("Oversized protobuf integer");
                result |= (long) (value & 127) << shift;
                if ((value & 128) == 0) return result;
            }
            throw new IOException("Oversized protobuf integer");
        }

        int length() throws IOException {
            long length = varint();
            if (length < 0 || length > remaining()) throw new IOException("Truncated protobuf data");
            return (int) length;
        }

        byte[] bytes() throws IOException {
            int length = length();
            byte[] result = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            return result;
        }

        void skip(int wire) throws IOException {
            if (wire == 0) { varint(); return; }
            int length;
            if (wire == 1) length = 8;
            else if (wire == 2) length = length();
            else if (wire == 5) length = 4;
            else throw new IOException("Unsupported protobuf wire type");
            if (length > remaining()) throw new IOException("Truncated protobuf field");
            offset += length;
        }
    }
}
