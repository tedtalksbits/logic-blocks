package dev.whizzle.logic.model;

import java.util.List;

/** Pure rule evaluation: no Minecraft types, so the same code is unit-tested and shared with the client. */
public final class Engine {
    private Engine() {}

    /** What a Logic Block can see. Implemented over the real world by the plugin and by fakes in tests. */
    public interface Inputs {
        /** Block id on that side, e.g. "red_wool" (no namespace for vanilla). */
        String block(Side side);
        /** Redstone signal 0..15 arriving from that side; ANY = strongest. */
        int signal(Side side);
        /** Items of that id ("*" = any) in the container on that side, or -1 if there is no container. */
        int items(Side side, String id);
        /** Players within {@code radius} blocks. */
        int players(int radius);
    }

    /**
     * Result of checking every condition. {@code matched} is the first output rule that holds, or -1 for "otherwise";
     * {@code alsoTrue} says which "also" rules hold. {@code truth} and {@code readings} cover the output rules, then the
     * "also" rules. {@code signals} holds each side's signal where a turns-on/off condition read it (-1 elsewhere), for
     * the next check to compare with.
     */
    public record Evaluation(int matched, boolean[][] truth, String[][] readings, boolean[] alsoTrue, int[] signals) {
        public Evaluation(int matched, boolean[][] truth, String[][] readings) { this(matched, truth, readings, new boolean[0], unknownSignals()); }
    }

    /** Signal per side (Side ordinal, ANY included) as last seen; -1 = unknown, so nothing "turns on" at the first check. */
    public static int[] unknownSignals() { int[] a = new int[Side.values().length]; java.util.Arrays.fill(a, -1); return a; }

    public static Evaluation evaluate(Program program, Inputs in) { return evaluate(program, in, null); }

    /** {@code previous}: the signals the last check saw ({@link Evaluation#signals()}, merged), or null if unknown. */
    public static Evaluation evaluate(Program program, Inputs in, int[] previous) {
        List<Program.Rule> rules = new java.util.ArrayList<>(program.rules());
        int outputs = rules.size();
        rules.addAll(program.also());
        boolean[][] truth = new boolean[rules.size()][];
        String[][] readings = new String[rules.size()][];
        boolean[] alsoTrue = new boolean[program.also().size()];
        int[] seen = unknownSignals();
        int matched = -1;
        for (int r = 0; r < rules.size(); r++) {
            List<Condition> cs = rules.get(r).conditions();
            truth[r] = new boolean[cs.size()];
            readings[r] = new String[cs.size()];
            boolean all = true;
            for (int c = 0; c < cs.size(); c++) {
                Condition cond = cs.get(c);
                boolean ok;
                String reading;
                switch (cond.kind()) {
                    case BLOCK -> {
                        String id = in.block(cond.side());
                        reading = id;
                        ok = cond.op() == Condition.Op.IS ? id.equals(cond.id()) : !id.equals(cond.id());
                    }
                    case SIGNAL -> {
                        int s = in.signal(cond.side());
                        reading = String.valueOf(s);
                        ok = cond.op().test(s, cond.number());
                    }
                    case ITEMS -> {
                        int n = in.items(cond.side(), cond.id());
                        reading = n < 0 ? "no container" : String.valueOf(n);
                        ok = n >= 0 && cond.op().test(n, cond.number());
                    }
                    case PLAYERS -> {
                        int n = in.players(cond.number2());
                        reading = String.valueOf(n);
                        ok = cond.op().test(n, cond.number());
                    }
                    case CHANGE -> {
                        int now = in.signal(cond.side());
                        int before = previous == null ? -1 : previous[cond.side().ordinal()];
                        seen[cond.side().ordinal()] = now;
                        reading = before < 0 ? "signal " + now : "signal " + before + " -> " + now;
                        ok = before >= 0 && (cond.turnsOn() ? before == 0 && now > 0 : before > 0 && now == 0);
                    }
                    default -> throw new IllegalStateException();
                }
                truth[r][c] = ok;
                readings[r][c] = reading;
                all &= ok;
            }
            if (r < outputs) { if (all && matched < 0) matched = r; }
            else alsoTrue[r - outputs] = all;
        }
        return new Evaluation(matched, truth, readings, alsoTrue, seen);
    }

    /** Keeps the signals this check saw for the next one; sides it didn't read keep their older value. */
    public static void remember(int[] previous, Evaluation e) {
        for (int i = 0; i < previous.length; i++) if (e.signals()[i] >= 0) previous[i] = e.signals()[i];
    }

    /**
     * The world actions this check runs, in order: the matched output rule's extra actions, then every true "also"
     * rule's actions.
     */
    public static List<Action> worldActions(Program program, Evaluation e) {
        List<Action> out = new java.util.ArrayList<>();
        if (e.matched() >= 0) {
            List<Action> as = program.rules().get(e.matched()).actions();
            for (int i = 1; i < as.size(); i++) out.add(as.get(i));
        }
        for (int r = 0; r < program.also().size(); r++) if (e.alsoTrue()[r]) out.addAll(program.also().get(r).actions());
        return out;
    }

    /** A block's memory between evaluations. {@code matched} -2 means never evaluated. */
    public static final class State {
        public int matched = -2, output;
        public long pulseUntil = -1;
        public boolean toggled;
        /** Signals the last check saw, for turns-on/off (not stored: after a reload nothing "turns on" at once). */
        public final int[] signals = unknownSignals();
        /** Tick before which this block may not move another item (hopper speed). */
        public long nextMove;

        public State() {}
        public State(int matched, int output, long pulseUntil, boolean toggled) {
            this.matched = matched; this.output = output; this.pulseUntil = pulseUntil; this.toggled = toggled;
        }
    }

    /** Applies the matched rule's action to the state at {@code tick} and returns the output (0..15). */
    public static int step(Program program, State state, int matched, long tick) {
        boolean entered = matched != state.matched;
        state.matched = matched;
        Action a = matched >= 0 ? program.rules().get(matched).action() : program.otherwise();
        switch (a.kind()) {
            case SET -> { state.output = a.strength(); state.pulseUntil = -1; }
            case PULSE -> { if (entered) { state.output = a.strength(); state.pulseUntil = tick + a.ticks(); } }
            case TOGGLE -> { if (entered) { state.toggled = !state.toggled; state.output = state.toggled ? a.strength() : 0; state.pulseUntil = -1; } }
            case KEEP -> { }
        }
        if (state.pulseUntil >= 0 && tick >= state.pulseUntil) { state.output = 0; state.pulseUntil = -1; }
        return state.output;
    }
}
