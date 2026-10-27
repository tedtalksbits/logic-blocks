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
        SET, PULSE, TOGGLE, KEEP, MOVE;
        /** Decides the redstone output (as opposed to acting on the world). */
        public boolean output() { return this != MOVE; }
    }

    public static final int MAX_TICKS = 1200;

    public Action {
        Objects.requireNonNull(kind);
        id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
        // Fields a kind doesn't use are cleared, so equal actions compare and encode equal.
        if (kind == Kind.MOVE) { strength = 0; ticks = 0; }
        else { id = ""; from = Side.ANY; to = Side.ANY; }
        if (kind == Kind.KEEP) strength = 0;
        if (kind != Kind.PULSE) ticks = 0;
        Objects.requireNonNull(from); Objects.requireNonNull(to);
    }

    public Action(Kind kind, int strength, int ticks) { this(kind, strength, ticks, "", Side.ANY, Side.ANY); }

    public static Action set(int strength) { return new Action(Kind.SET, strength, 0); }
    public static Action pulse(int strength, int ticks) { return new Action(Kind.PULSE, strength, ticks); }
    public static Action toggle(int strength) { return new Action(Kind.TOGGLE, strength, 0); }
    public static Action keep() { return new Action(Kind.KEEP, 0, 0); }
    public static Action move(String id, Side from, Side to) { return new Action(Kind.MOVE, 0, 0, id, from, to); }

    public String problem() {
        if (kind == Kind.MOVE) {
            if (from == Side.ANY || to == Side.ANY) return "Pick the sides to move from and to";
            if (from == to) return "Move from one side to a different side";
            if (!id.equals("*") && !Condition.validId(id)) return "Name an item to move, like coal, or * for any item";
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
        };
    }

    /** Format 2: every field. */
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
