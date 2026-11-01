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
        /** The block's own counter {@code i} (0 to 3 for A to D). */
        default int counter(int i) { return 0; }
        /** Ticks left on the block's timer {@code i} (0 to 3 for T1 to T4); 0 when it isn't running. */
        default int timerLeft(int i) { return 0; }
        /** Whether timer {@code i} ran out at this check. */
        default boolean timerDone(int i) { return false; }
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
                    case COUNTER -> {
                        int i = Condition.counterIndex(cond.id());
                        int v = i < 0 ? 0 : in.counter(i);
                        reading = Condition.memoryLabel(cond.id()) + " = " + v;
                        ok = i >= 0 && cond.op().test(v, cond.number());
                    }
                    case TIMER -> {
                        int i = Condition.timerIndex(cond.id());
                        int left = i < 0 ? 0 : in.timerLeft(i);
                        boolean done = i >= 0 && in.timerDone(i);
                        reading = Condition.memoryLabel(cond.id()) + (done ? " done" : left > 0 ? " running, " + Action.seconds(left) + " left" : " not running");
                        boolean state = cond.number() == Condition.RUNNING ? left > 0 : done;
                        ok = i >= 0 && (cond.op() == Condition.Op.IS) == state;
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
     * The world actions (moves) this check runs, in order: the matched output rule's, then every true "also" rule's.
     * Unlike counter and timer actions they repeat every check while their rule is true.
     */
    public static List<Action> worldActions(Program program, Evaluation e) {
        List<Action> out = new java.util.ArrayList<>();
        if (e.matched() >= 0) {
            List<Action> as = program.rules().get(e.matched()).actions();
            for (int i = 1; i < as.size(); i++) if (as.get(i).kind() == Action.Kind.MOVE) out.add(as.get(i));
        }
        for (int r = 0; r < program.also().size(); r++) if (e.alsoTrue()[r])
            for (Action a : program.also().get(r).actions()) if (a.kind() == Action.Kind.MOVE) out.add(a);
        return out;
    }

    /** A block's memory between evaluations. {@code matched} -2 means never evaluated. */
    public static final class State {
        public int matched = -2, output;
        public long pulseUntil = -1;
        public boolean toggled;
        /** Counters A to D. */
        public final int[] counters = new int[Condition.COUNTERS];
        /** Tick each timer runs out at, or -1 when it isn't running. */
        public final long[] timerEnd = unset();
        /** Timers that ran out at the last check (bit per timer): "done" is true for that one check. */
        public int timerDone;
        /** Which "when" rules were true at the last check (bit per rule), so their counter and timer actions run once per becoming true. */
        public int alsoWas;
        /** Set when counters, timers or alsoWas change, so the plugin knows to save. */
        public boolean memoryChanged;

        private static long[] unset() { long[] a = new long[Condition.TIMERS]; java.util.Arrays.fill(a, -1); return a; }

        /** Back to a fresh block: output 0, toggles off, pulses stopped, counters 0, timers stopped. */
        public void reset() {
            matched = -2; output = 0; pulseUntil = -1; toggled = false;
            java.util.Arrays.fill(counters, 0); java.util.Arrays.fill(timerEnd, -1); timerDone = 0; alsoWas = 0; memoryChanged = true;
            java.util.Arrays.fill(signals, -1);
        }

        /** The block's memory as condition inputs at {@code tick}, over {@code world}. */
        public Inputs over(Inputs world, long tick) {
            State st = this;
            return new Inputs() {
                @Override public String block(Side side) { return world.block(side); }
                @Override public int signal(Side side) { return world.signal(side); }
                @Override public int items(Side side, String id) { return world.items(side, id); }
                @Override public int players(int radius) { return world.players(radius); }
                @Override public int counter(int i) { return st.counters[i]; }
                @Override public int timerLeft(int i) { return st.timerEnd[i] < 0 ? 0 : (int) Math.max(1, st.timerEnd[i] - tick); }
                @Override public boolean timerDone(int i) { return (st.timerDone >> i & 1) != 0; }
            };
        }
        /** Signals the last check saw, for turns-on/off (not stored: after a reload nothing "turns on" at once). */
        public final int[] signals = unknownSignals();
        /** Tick before which this block may not move another item (hopper speed). */
        public long nextMove;

        public State() {}
        public State(int matched, int output, long pulseUntil, boolean toggled) {
            this.matched = matched; this.output = output; this.pulseUntil = pulseUntil; this.toggled = toggled;
        }
    }

    /**
     * One full check at {@code tick}: timers that ran out become "done" for this check, every condition is evaluated
     * (all of them see the counters and timers as they were at the start of the check), then counter and timer
     * actions run (the matched If/Else rule's when it has just started matching, each "when" rule's when it has just
     * become true; If/Else first, then the "when" rules from the top, in order), then the output is updated. Moves are
     * left to the caller ({@link #worldActions}).
     */
    public static Evaluation run(Program program, Inputs world, State state, long tick) {
        int done = 0;
        for (int i = 0; i < state.timerEnd.length; i++)
            if (state.timerEnd[i] >= 0 && tick >= state.timerEnd[i]) { state.timerEnd[i] = -1; done |= 1 << i; }
        if (done != 0 || state.timerDone != 0) state.memoryChanged = true;
        state.timerDone = done;
        Evaluation e = evaluate(program, state.over(world, tick), state.signals);
        remember(state.signals, e);
        if (e.matched() >= 0 && e.matched() != state.matched) {
            List<Action> as = program.rules().get(e.matched()).actions();
            for (int i = 1; i < as.size(); i++) applyMemory(as.get(i), state, tick);
        }
        int now = 0;
        for (int r = 0; r < program.also().size(); r++) {
            if (!e.alsoTrue()[r]) continue;
            now |= 1 << r;
            if ((state.alsoWas >> r & 1) == 0) for (Action a : program.also().get(r).actions()) applyMemory(a, state, tick);
        }
        if (now != state.alsoWas) { state.alsoWas = now; state.memoryChanged = true; }
        step(program, state, e.matched(), tick);
        return e;
    }

    private static void applyMemory(Action a, State state, long tick) {
        if (!a.kind().memory()) return;
        int c = Condition.counterIndex(a.id()), t = Condition.timerIndex(a.id());
        switch (a.kind()) {
            case ADD -> { if (c >= 0) state.counters[c] = Math.min(Condition.MAX_NUMBER, state.counters[c] + a.strength()); }
            case SUBTRACT -> { if (c >= 0) state.counters[c] = Math.max(0, state.counters[c] - a.strength()); }
            case SET_COUNTER -> { if (c >= 0) state.counters[c] = Math.clamp(a.strength(), 0, Condition.MAX_NUMBER); }
            case START -> { if (t >= 0) state.timerEnd[t] = tick + a.ticks(); }
            case STOP -> { if (t >= 0) state.timerEnd[t] = -1; }
            default -> { }
        }
        state.memoryChanged = true;
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
            default -> { }   // KEEP
        }
        if (state.pulseUntil >= 0 && tick >= state.pulseUntil) { state.output = 0; state.pulseUntil = -1; }
        return state.output;
    }
}
