package dev.whizzle.logic.model;

/**
 * What the block outputs when a rule matches (or for "otherwise").
 * <ul>
 *   <li>SET: output {@code strength} for as long as the rule matches.</li>
 *   <li>PULSE: when the rule starts matching, output {@code strength} for {@code ticks} ticks, then 0.</li>
 *   <li>TOGGLE: when the rule starts matching, flip between {@code strength} and 0. The output then stays put.</li>
 *   <li>KEEP: leave the output as it is (useful as "otherwise" so a toggle or pulse isn't overwritten).</li>
 * </ul>
 */
public record Action(Kind kind, int strength, int ticks) {
    public enum Kind { SET, PULSE, TOGGLE, KEEP }

    public static final int MAX_TICKS = 1200;

    public static Action set(int strength) { return new Action(Kind.SET, strength, 0); }
    public static Action pulse(int strength, int ticks) { return new Action(Kind.PULSE, strength, ticks); }
    public static Action toggle(int strength) { return new Action(Kind.TOGGLE, strength, 0); }
    public static Action keep() { return new Action(Kind.KEEP, 0, 0); }

    public String problem() {
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
        };
    }

    void write(Wire.Writer out) { out.varInt(kind.ordinal()).varInt(strength).varInt(ticks); }

    static Action read(Wire.Reader in) { return new Action(Condition.pick(Kind.values(), in.varInt()), in.varInt(), in.varInt()); }
}
