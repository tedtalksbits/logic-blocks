package dev.whizzle.logic.model;

import java.util.*;

/**
 * Everything a Logic Block can see at one moment, sent to the client while its editor is open so the client can
 * evaluate the rules being edited (with the same Engine) and show live true/false before saving.
 */
public record Snapshot(String[] blocks, int[] signals, List<Map<String, Integer>> containers, int[] playerDistances,
                       int output, int matched, int[] counters, int[] timersLeft, int timerDone, int[] fills) implements Engine.Inputs {
    public static final int MAX_ITEM_TYPES = 64, MAX_PLAYERS = 64;

    /** Without memory: counters 0, no timers running. */
    public Snapshot(String[] blocks, int[] signals, List<Map<String, Integer>> containers, int[] playerDistances, int output, int matched) {
        this(blocks, signals, containers, playerDistances, output, matched, new int[Condition.COUNTERS], new int[Condition.TIMERS], 0, noFills());
    }

    /** Fill per side unknown: -1 everywhere (no container). */
    public static int[] noFills() { int[] a = new int[6]; java.util.Arrays.fill(a, -1); return a; }

    /** This snapshot with each side's fill level (-1: no container). */
    public Snapshot withFills(int[] fills) {
        return new Snapshot(blocks, signals, containers, playerDistances, output, matched, counters, timersLeft, timerDone, fills.clone());
    }

    @Override public int fill(Side side) { return side == Side.ANY ? -1 : fills[side.ordinal()]; }

    /** This snapshot with the block's memory (counters, timers) as of {@code tick}. */
    public Snapshot withMemory(Engine.State state, long tick) {
        int[] c = state.counters.clone(), t = new int[Condition.TIMERS];
        Engine.Inputs mem = state.over(this, tick);
        for (int i = 0; i < t.length; i++) t[i] = mem.timerLeft(i);
        return new Snapshot(blocks, signals, containers, playerDistances, output, matched, c, t, state.timerDone, fills);
    }

    @Override public int counter(int i) { return counters[i]; }
    @Override public int timerLeft(int i) { return timersLeft[i]; }
    @Override public boolean timerDone(int i) { return (timerDone >> i & 1) != 0; }

    /** Player distances are stored in tenths of a block. */
    public static int tenths(double distance) { return (int) Math.ceil(distance * 10 - 1e-9); }

    @Override public String block(Side side) { return side == Side.ANY ? "" : blocks[side.ordinal()]; }

    @Override public int signal(Side side) {
        if (side != Side.ANY) return signals[side.ordinal()];
        int max = 0;
        for (int s : signals) max = Math.max(max, s);
        return max;
    }

    @Override public int items(Side side, String id) {
        if (side == Side.ANY) return -1;
        Map<String, Integer> c = containers.get(side.ordinal());
        if (c == null) return -1;
        if (id.equals("*")) return c.values().stream().mapToInt(Integer::intValue).sum();
        return c.getOrDefault(id, 0);
    }

    @Override public int players(int radius) {
        int n = 0;
        for (int d : playerDistances) if (d <= radius * 10) n++;
        return n;
    }

    public byte[] encode() {
        Wire.Writer out = new Wire.Writer();
        for (int i = 0; i < 6; i++) {
            out.utf(blocks[i]).varInt(signals[i]);
            Map<String, Integer> c = containers.get(i);
            out.bool(c != null);
            if (c != null) {
                out.varInt(c.size());
                c.forEach((k, v) -> out.utf(k).varInt(v));
            }
        }
        out.varInt(playerDistances.length);
        for (int d : playerDistances) out.varInt(d);
        out.varInt(output).varInt(matched + 1);
        for (int c : counters) out.varInt(c);
        for (int t : timersLeft) out.varInt(t);
        out.varInt(timerDone);
        for (int f : fills) out.varInt(f + 1);
        return out.bytes();
    }

    public static Snapshot decode(Wire.Reader in) {
        String[] blocks = new String[6];
        int[] signals = new int[6];
        List<Map<String, Integer>> containers = new ArrayList<>(6);
        for (int i = 0; i < 6; i++) {
            blocks[i] = in.utf(256);
            signals[i] = in.varInt();
            if (in.bool()) {
                int n = in.varInt();
                if (n < 0 || n > MAX_ITEM_TYPES) throw new IllegalArgumentException("Too many item types");
                Map<String, Integer> c = new TreeMap<>();
                for (int j = 0; j < n; j++) c.put(in.utf(256), in.varInt());
                containers.add(c);
            } else containers.add(null);
        }
        int p = in.varInt();
        if (p < 0 || p > MAX_PLAYERS) throw new IllegalArgumentException("Too many players");
        int[] distances = new int[p];
        for (int i = 0; i < p; i++) distances[i] = in.varInt();
        int output = in.varInt(), matched = in.varInt() - 1;
        int[] counters = new int[Condition.COUNTERS], timers = new int[Condition.TIMERS];
        for (int i = 0; i < counters.length; i++) counters[i] = in.varInt();
        for (int i = 0; i < timers.length; i++) timers[i] = in.varInt();
        int done = in.varInt();
        int[] fills = new int[6];
        for (int i = 0; i < 6; i++) fills[i] = Math.clamp(in.varInt() - 1, -1, 15);
        return new Snapshot(blocks, signals, containers, distances, output, matched, counters, timers, done, fills);
    }

    /** Keeps the {@link #MAX_ITEM_TYPES} most numerous kinds (live preview only; the server evaluates exact counts). */
    public static Map<String, Integer> fold(Map<String, Integer> counts) {
        if (counts.size() <= MAX_ITEM_TYPES) return new TreeMap<>(counts);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(counts.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        Map<String, Integer> out = new TreeMap<>();
        for (int i = 0; i < MAX_ITEM_TYPES; i++) out.put(sorted.get(i).getKey(), sorted.get(i).getValue());
        return out;
    }
}
