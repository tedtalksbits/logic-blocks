package dev.whizzle.logic.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The Logic Blocks in one chunk, as the server sends them to the mod so it can draw them ({@code chunk} message), and
 * the mod's request for chunks it had before the server welcomed it ({@code chunks}). Pure Java, shared.
 *
 * Positions are packed into one int: x and z within the chunk (0-15) and y (-2048 to 2047).
 */
public record ChunkList(int cx, int cz, List<Integer> packed) {
    /** At most this many chunks per {@code chunks} request; at most this many Logic Blocks per chunk in a message. */
    public static final int MAX_CHUNKS = 64, MAX_BLOCKS = 4096;

    public ChunkList { packed = List.copyOf(packed); }

    public static int pack(int x, int y, int z) { return ((y + 2048) << 8) | ((z & 15) << 4) | (x & 15); }
    public static int x(int packed) { return packed & 15; }
    public static int z(int packed) { return (packed >> 4) & 15; }
    public static int y(int packed) { return (packed >> 8) - 2048; }

    /** Bukkit's chunk key ({@code Chunk.getChunkKey}): x in the low 32 bits, z in the high. */
    public static long key(int cx, int cz) { return (cx & 0xffffffffL) | ((cz & 0xffffffffL) << 32); }
    public static int keyX(long key) { return (int) key; }
    public static int keyZ(long key) { return (int) (key >>> 32); }

    public byte[] encode() {
        Wire.Writer out = new Wire.Writer().int32(cx).int32(cz).varInt(packed.size());
        for (int p : packed) out.varInt(p);
        return out.bytes();
    }

    public static ChunkList decode(byte[] data) {
        Wire.Reader in = new Wire.Reader(data);
        int cx = in.int32(), cz = in.int32(), n = in.varInt();
        if (n < 0 || n > MAX_BLOCKS) throw new IllegalArgumentException("Too many Logic Blocks in a chunk");
        List<Integer> packed = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int p = in.varInt();
            if (p < 0 || y(p) > 2047) throw new IllegalArgumentException("Bad position");
            packed.add(p);
        }
        in.end();
        return new ChunkList(cx, cz, packed);
    }

    /** The {@code chunks} request: chunk positions as keys. */
    public static byte[] request(List<Long> keys) {
        if (keys.size() > MAX_CHUNKS) throw new IllegalArgumentException("At most " + MAX_CHUNKS + " chunks");
        Wire.Writer out = new Wire.Writer().varInt(keys.size());
        for (long k : keys) out.int32(keyX(k)).int32(keyZ(k));
        return out.bytes();
    }

    public static List<Long> readRequest(byte[] data) {
        Wire.Reader in = new Wire.Reader(data);
        int n = in.varInt();
        if (n < 0 || n > MAX_CHUNKS) throw new IllegalArgumentException("At most " + MAX_CHUNKS + " chunks");
        List<Long> keys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) keys.add(key(in.int32(), in.int32()));
        in.end();
        return keys;
    }
}
