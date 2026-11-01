package dev.whizzle.logic.model;

import java.util.Locale;
import java.util.Objects;

/**
 * Something a rule does. Output actions decide the block's redstone output; the others act on the world.
 * <ul>
 *   <li>SET: output {@code strength} for as long as the rule matches.</li>
 *   <li>PULSE: when the rule starts matching, output {@code strength} for {@code ticks} ticks, then 0.</li>
 *   <li>TOGGLE: when the rule starts matching, flip between {@code strength} and 0. The output then stays put.</li>
 *   <li>KEEP: leave the output as it is (useful as "otherwise" so a toggle or pulse isn't overwritten).</li>
 *   <li>MOVE: move one {@code id} item ("*" = any) from the container on {@code from} to the container on
 *       {@code to}, at hopper speed (see docs/DESIGN.md, "Beyond redstone"). Not an output action.</li>
 * </ul>
 */
public record Action(Kind kind, int strength, int ticks, String id, Side from, Side to) {
    public enum Kind {   // append only: stored by ordinal
        SET, PULSE, TOGGLE, KEEP, MOVE, ADD, SUBTRACT, SET_COUNTER, START, STOP;
        /** Decides the redstone output (as opposed to acting on the world or the block's memory). */
        public boolean output() { return this == SET || this == PULSE || this == TOGGLE || this == KEEP; }
        /** Changes the block's own counters or timers. Runs once each time its rule becomes true. */
        public boolean memory() { return this == ADD || this == SUBTRACT || this == SET_COUNTER || this == START || this == STOP; }
        /** Changes a counter (its id is "a" to "d"; the amount or value is in {@code strength}). */
        public boolean counter() { return this == ADD || this == SUBTRACT || this == SET_COUNTER; }
    }

    public static final int MAX_TICKS = 1200;
    /** Longest timer: one hour. */
    public static final int MAX_TIMER_TICKS = 72_000;

    public Action {
        Objects.requireNonNull(kind);
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
        // Fields a kind doesn't use are cleared, so equal actions compare and encode equal.
        if (kind == Kind.MOVE) { strength = 0; ticks = 0; }
        else { from = Side.ANY; to = Side.ANY; }
        if (!kind.memory() && kind != Kind.MOVE) id = "";
        if (kind == Kind.KEEP || kind == Kind.START || kind == Kind.STOP) strength = 0;
        if (kind != Kind.PULSE && kind != Kind.START) ticks = 0;
        Objects.requireNonNull(from); Objects.requireNonNull(to);
    }

    public Action(Kind kind, int strength, int ticks) { this(kind, strength, ticks, "", Side.ANY, Side.ANY); }

    public static Action set(int strength) { return new Action(Kind.SET, strength, 0); }
    public static Action pulse(int strength, int ticks) { return new Action(Kind.PULSE, strength, ticks); }
    public static Action toggle(int strength) { return new Action(Kind.TOGGLE, strength, 0); }
    public static Action keep() { return new Action(Kind.KEEP, 0, 0); }
    public static Action move(String id, Side from, Side to) { return new Action(Kind.MOVE, 0, 0, id, from, to); }
    /** Adds {@code amount} to counter {@code counter} ("a" to "d"). */
    public static Action add(String counter, int amount) { return new Action(Kind.ADD, amount, 0, counter, Side.ANY, Side.ANY); }
    public static Action subtract(String counter, int amount) { return new Action(Kind.SUBTRACT, amount, 0, counter, Side.ANY, Side.ANY); }
    public static Action setCounter(String counter, int value) { return new Action(Kind.SET_COUNTER, value, 0, counter, Side.ANY, Side.ANY); }
    /** (Re)starts timer {@code timer} ("t1" to "t4") to run out after {@code ticks}. */
    public static Action start(String timer, int ticks) { return new Action(Kind.START, 0, ticks, timer, Side.ANY, Side.ANY); }
    public static Action stop(String timer) { return new Action(Kind.STOP, 0, 0, timer, Side.ANY, Side.ANY); }

    /** "5s", "0.5s", "1.25s": a tick count as seconds. */
    public static String seconds(int ticks) {
        String s = java.math.BigDecimal.valueOf(ticks).divide(java.math.BigDecimal.valueOf(20)).stripTrailingZeros().toPlainString();
        return s + "s";
    }

    public String problem() {
        if (kind == Kind.MOVE) {
            if (from == Side.ANY || to == Side.ANY) return "Pick the sides to move from and to";
            if (from == to) return "Move from one side to a different side";
            if (!id.equals("*") && !Condition.validId(id)) return "Name an item to move, like coal, or * for any item";
            return null;
        }
        if (kind.counter()) {
            if (Condition.counterIndex(id) < 0) return "Pick a counter, A to D";
            if (kind == Kind.SET_COUNTER ? strength < 0 || strength > Condition.MAX_NUMBER : strength < 1 || strength > Condition.MAX_NUMBER)
                return (kind == Kind.SET_COUNTER ? "A counter is 0 to " : "Add or subtract 1 to ") + Condition.MAX_NUMBER;
            return null;
        }
        if (kind == Kind.START || kind == Kind.STOP) {
            if (Condition.timerIndex(id) < 0) return "Pick a timer, T1 to T4";
            if (kind == Kind.START && (ticks < 1 || ticks > MAX_TIMER_TICKS)) return "A timer runs 0.05 to " + MAX_TIMER_TICKS / 20 + " seconds";
            return null;
        }
        if (kind != Kind.KEEP && (strength < 0 || strength > 15)) return "Output strength is 0 to 15";
        if (kind == Kind.PULSE && (ticks < 1 || ticks > MAX_TICKS)) return "Pulse length is 1 to " + MAX_TICKS + " ticks (20 ticks = 1 second)";
        return null;
    }

    public String describe() {
        return switch (kind) {
            case SET -> "output " + strength;
            case PULSE -> "pulse " + strength + " for " + ticks + " ticks";
            case TOGGLE -> "toggle " + strength;
            case KEEP -> "keep output";
            case MOVE -> "move " + (id.equals("*") ? "any item" : id) + " from " + from.label().toLowerCase(Locale.ROOT) + " to " + to.label().toLowerCase(Locale.ROOT);
            case ADD -> "add " + strength + " to " + Condition.memoryLabel(id);
            case SUBTRACT -> "subtract " + strength + " from " + Condition.memoryLabel(id);
            case SET_COUNTER -> "set " + Condition.memoryLabel(id) + " to " + strength;
            case START -> "start " + Condition.memoryLabel(id) + " for " + seconds(ticks);
            case STOP -> "stop " + Condition.memoryLabel(id);
        };
    }

    /** Formats 2 and 3: every field. */
    void write(Wire.Writer out) {
        out.varInt(kind.ordinal()).varInt(strength).varInt(ticks).utf(id).varInt(from.ordinal()).varInt(to.ordinal());
    }

    static Action read(Wire.Reader in) {
        return new Action(Condition.pick(Kind.values(), in.varInt()), in.varInt(), in.varInt(), in.utf(Condition.MAX_ID * 4),
            Condition.pick(Side.values(), in.varInt()), Condition.pick(Side.values(), in.varInt()));
    }

    /** Format 1 (v1 programs): kind, strength, ticks. */
    static Action readV1(Wire.Reader in) {
        Kind kind = Condition.pick(Kind.values(), in.varInt());
        if (!kind.output()) throw new IllegalArgumentException("Unknown action " + kind.ordinal());
        return new Action(kind, in.varInt(), in.varInt());
    }
}
