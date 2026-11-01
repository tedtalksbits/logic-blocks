package dev.whizzle.logic.model;

import java.util.Locale;
import java.util.Objects;

/**
 * One test against the world. Which fields matter depends on the kind:
 * <ul>
 *   <li>BLOCK: the block on {@code side} {@code is} / {@code is not} {@code id} (e.g. "red_wool").</li>
 *   <li>SIGNAL: the redstone signal coming in on {@code side} (or ANY: the strongest), compared with {@code number}.</li>
 *   <li>ITEMS: how many {@code id} items (or "*" for any) the container on {@code side} holds, compared with {@code number}.</li>
 *   <li>PLAYERS: how many players are within {@code number2} blocks, compared with {@code number}.</li>
 *   <li>CHANGE: the signal on {@code side} (or ANY: the strongest) has just turned on ({@code number} 1: it was 0 at
 *       the previous check and isn't now) or off ({@code number} 0). True for one check only, so a held button
 *       counts once.</li>
 *   <li>COUNTER: this block's counter {@code id} ("a" to "d") compared with {@code number}.</li>
 *   <li>TIMER: this block's timer {@code id} ("t1" to "t4") {@code is} / {@code is not} done ({@code number} 0: it ran
 *       out at this check, true for one check only) or running ({@code number} 1).</li>
 * </ul>
 * Counters and timers are the block's own memory: no other block can see them.
 */
public record Condition(Kind kind, Side side, Op op, String id, int number, int number2) {
    public enum Kind { BLOCK, SIGNAL, ITEMS, PLAYERS, CHANGE, COUNTER, TIMER }   // append only: stored by ordinal

    public enum Op {
        IS("is"), IS_NOT("is not"), EQ("="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");
        public final String symbol;
        Op(String symbol) { this.symbol = symbol; }
        public boolean numeric() { return this != IS && this != IS_NOT; }
        public boolean test(int value, int target) {
            return switch (this) {
                case EQ, IS -> value == target;
                case NE, IS_NOT -> value != target;
                case LT -> value < target;
                case LE -> value <= target;
                case GT -> value > target;
                case GE -> value >= target;
            };
        }
    }

    public static final int MAX_NUMBER = 100_000, MAX_RADIUS = 16, MAX_ID = 64;
    /** Each block has counters A to D and timers T1 to T4. */
    public static final int COUNTERS = 4, TIMERS = 4;
    public static final int DONE = 0, RUNNING = 1;

    /** "a" to "d" -> 0 to 3; -1 for anything else. */
    public static int counterIndex(String id) {
        return id.length() == 1 && id.charAt(0) >= 'a' && id.charAt(0) < 'a' + COUNTERS ? id.charAt(0) - 'a' : -1;
    }
    /** "t1" to "t4" -> 0 to 3; -1 for anything else. */
    public static int timerIndex(String id) {
        return id.length() == 2 && id.charAt(0) == 't' && id.charAt(1) >= '1' && id.charAt(1) < '1' + TIMERS ? id.charAt(1) - '1' : -1;
    }
    public static String counterId(int i) { return String.valueOf((char) ('a' + i)); }
    public static String timerId(int i) { return "t" + (i + 1); }
    /** "a" -> "A", "t1" -> "T1": how players see them. */
    public static String memoryLabel(String id) { return id.toUpperCase(Locale.ROOT); }

    public Condition {
        Objects.requireNonNull(kind); Objects.requireNonNull(side); Objects.requireNonNull(op);
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
        // Fields a kind doesn't use are cleared, so equal rules compare and encode equal.
        switch (kind) {
            case BLOCK -> { number = 0; number2 = 0; }
            case SIGNAL -> { id = ""; number2 = 0; }
            case ITEMS -> number2 = 0;
            case PLAYERS -> { side = Side.ANY; id = ""; }
            case CHANGE -> { op = Op.EQ; id = ""; number = number == 0 ? 0 : 1; number2 = 0; }
            case COUNTER -> { side = Side.ANY; number2 = 0; }
            case TIMER -> { side = Side.ANY; number = number == DONE ? DONE : RUNNING; number2 = 0; }
        }
    }

    public static Condition block(Side side, boolean is, String id) { return new Condition(Kind.BLOCK, side, is ? Op.IS : Op.IS_NOT, id, 0, 0); }
    public static Condition signal(Side side, Op op, int strength) { return new Condition(Kind.SIGNAL, side, op, "", strength, 0); }
    public static Condition items(Side side, String id, Op op, int count) { return new Condition(Kind.ITEMS, side, op, id, count, 0); }
    public static Condition players(int radius, Op op, int count) { return new Condition(Kind.PLAYERS, Side.ANY, op, "", count, radius); }
    /** The signal on {@code side} turns on ({@code on}) or off. */
    public static Condition change(Side side, boolean on) { return new Condition(Kind.CHANGE, side, Op.EQ, "", on ? 1 : 0, 0); }
    public boolean turnsOn() { return kind == Kind.CHANGE && number == 1; }
    /** Counter {@code counter} ("a" to "d") compared with {@code value}. */
    public static Condition counter(String counter, Op op, int value) { return new Condition(Kind.COUNTER, Side.ANY, op, counter, value, 0); }
    /** Timer {@code timer} ("t1" to "t4") is (or with {@code is} false, isn't) done / running. */
    public static Condition timer(String timer, boolean is, boolean running) { return new Condition(Kind.TIMER, Side.ANY, is ? Op.IS : Op.IS_NOT, timer, running ? RUNNING : DONE, 0); }

    /** Null when valid, otherwise what's wrong, in words a player understands. */
    public String problem() {
        switch (kind) {
            case BLOCK -> {
                if (side == Side.ANY) return "Pick a side for the block check";
                if (op.numeric()) return "Block checks use \"is\" or \"is not\"";
                if (!validId(id)) return "Name a block, like red_wool";
            }
            case SIGNAL -> {
                if (!op.numeric()) return "Signal checks compare a number (=, <, >...)";
                if (number < 0 || number > 15) return "Signal strength is 0 to 15";
            }
            case ITEMS -> {
                if (side == Side.ANY) return "Pick the side the container is on";
                if (!op.numeric()) return "Item checks compare a number";
                if (!id.equals("*") && !validId(id)) return "Name an item, like coal, or * for any item";
                if (number < 0 || number > MAX_NUMBER) return "Item count is 0 to " + MAX_NUMBER;
            }
            case PLAYERS -> {
                if (!op.numeric()) return "Player checks compare a number";
                if (number2 < 1 || number2 > MAX_RADIUS) return "Distance is 1 to " + MAX_RADIUS + " blocks";
                if (number < 0 || number > 100) return "Player count is 0 to 100";
            }
            case CHANGE -> { }
            case COUNTER -> {
                if (counterIndex(id) < 0) return "Pick a counter, A to D";
                if (!op.numeric()) return "Counter checks compare a number";
                if (number < 0 || number > MAX_NUMBER) return "A counter is 0 to " + MAX_NUMBER;
            }
            case TIMER -> {
                if (timerIndex(id) < 0) return "Pick a timer, T1 to T4";
                if (op.numeric()) return "Timer checks use \"is\" or \"is not\"";
            }
        }
        return null;
    }

    static boolean validId(String id) { return id.length() <= MAX_ID && id.matches("[a-z0-9_./-]+"); }

    /** Human-readable, e.g. "north is red_wool", "signal west >= 5". */
    public String describe() {
        return switch (kind) {
            case BLOCK -> side.label().toLowerCase(Locale.ROOT) + " " + op.symbol + " " + id;
            case SIGNAL -> "signal " + (side == Side.ANY ? "any side" : side.label().toLowerCase(Locale.ROOT)) + " " + op.symbol + " " + number;
            case ITEMS -> (id.equals("*") ? "items" : id) + " " + side.label().toLowerCase(Locale.ROOT) + " " + op.symbol + " " + number;
            case PLAYERS -> "players within " + number2 + " " + op.symbol + " " + number;
            case CHANGE -> "signal " + (side == Side.ANY ? "any side" : side.label().toLowerCase(Locale.ROOT)) + (number == 1 ? " turns on" : " turns off");
            case COUNTER -> "counter " + memoryLabel(id) + " " + op.symbol + " " + number;
            case TIMER -> memoryLabel(id) + " " + op.symbol + (number == RUNNING ? " running" : " done");
        };
    }

    void write(Wire.Writer out) {
        out.varInt(kind.ordinal()).varInt(side.ordinal()).varInt(op.ordinal()).utf(id).varInt(number).varInt(number2);
    }

    static Condition read(Wire.Reader in) {
        return new Condition(pick(Kind.values(), in.varInt()), pick(Side.values(), in.varInt()), pick(Op.values(), in.varInt()),
            in.utf(MAX_ID * 4), in.varInt(), in.varInt());
    }

    static <T> T pick(T[] values, int index) {
        if (index < 0 || index >= values.length) throw new IllegalArgumentException("Unknown value " + index);
        return values[index];
    }
}
