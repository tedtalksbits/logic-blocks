package dev.whizzle.logic.model;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minecraft-compatible primitive encoding for plugin messages (VarInt, big-endian int, UTF-8 string with VarInt
 * byte length), so the Fabric client can read them with FriendlyByteBuf. Shared byte-for-byte with the client mod (docs/PROTOCOL.md).
 */
public final class Wire {
    private Wire() {}

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        public Writer varInt(int value) {
            while ((value & ~0x7F) != 0) { out.write((value & 0x7F) | 0x80); value >>>= 7; }
            out.write(value);
            return this;
        }
        public Writer int32(int value) { out.write(value >>> 24); out.write(value >>> 16); out.write(value >>> 8); out.write(value); return this; }
        public Writer bool(boolean value) { out.write(value ? 1 : 0); return this; }
        public Writer utf(String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 32767 * 3) throw new IllegalArgumentException("String too long");
            varInt(bytes.length);
            out.write(bytes, 0, bytes.length);
            return this;
        }
        /** VarInt length + raw bytes. */
        public Writer bytes(byte[] value) { varInt(value.length); out.write(value, 0, value.length); return this; }
        public byte[] bytes() { return out.toByteArray(); }
    }

    public static final class Reader {
        private final byte[] data;
        private int position;
        public Reader(byte[] data) { this.data = data; }
        private int next() {
            if (position >= data.length) throw new IllegalArgumentException("Truncated message");
            return data[position++] & 0xFF;
        }
        public int varInt() {
            int value = 0;
            for (int shift = 0; shift < 35; shift += 7) {
                int b = next();
                value |= (b & 0x7F) << shift;
                if ((b & 0x80) == 0) return value;
            }
            throw new IllegalArgumentException("VarInt too long");
        }
        public int int32() { return (next() << 24) | (next() << 16) | (next() << 8) | next(); }
        public boolean bool() { return next() != 0; }
        public String utf(int maxBytes) {
            int length = varInt();
            if (length < 0 || length > maxBytes || position + length > data.length) throw new IllegalArgumentException("Bad string length");
            String s = new String(data, position, length, StandardCharsets.UTF_8);
            position += length;
            return s;
        }
        public byte[] bytes(int maxLength) {
            int length = varInt();
            if (length < 0 || length > maxLength || position + length > data.length) throw new IllegalArgumentException("Bad byte array length");
            byte[] out = java.util.Arrays.copyOfRange(data, position, position + length);
            position += length;
            return out;
        }
        public void end() { if (position != data.length) throw new IllegalArgumentException("Trailing bytes"); }
    }
}
