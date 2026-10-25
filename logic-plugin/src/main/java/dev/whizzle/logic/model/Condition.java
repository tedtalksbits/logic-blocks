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
 * </ul>
 */
public record Condition(Kind kind, Side side, Op op, String id, int number, int number2) {
    public enum Kind { BLOCK, SIGNAL, ITEMS, PLAYERS }

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
        }
    }

    public static Condition block(Side side, boolean is, String id) { return new Condition(Kind.BLOCK, side, is ? Op.IS : Op.IS_NOT, id, 0, 0); }
    public static Condition signal(Side side, Op op, int strength) { return new Condition(Kind.SIGNAL, side, op, "", strength, 0); }
    public static Condition items(Side side, String id, Op op, int count) { return new Condition(Kind.ITEMS, side, op, id, count, 0); }
    public static Condition players(int radius, Op op, int count) { return new Condition(Kind.PLAYERS, Side.ANY, op, "", count, radius); }

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
