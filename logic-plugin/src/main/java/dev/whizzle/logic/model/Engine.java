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

    /** Result of checking every condition. {@code matched} is the first rule that holds, or -1 for "otherwise". */
    public record Evaluation(int matched, boolean[][] truth, String[][] readings) {}

    public static Evaluation evaluate(Program program, Inputs in) {
        List<Program.Rule> rules = program.rules();
        boolean[][] truth = new boolean[rules.size()][];
        String[][] readings = new String[rules.size()][];
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
                    default -> throw new IllegalStateException();
                }
                truth[r][c] = ok;
                readings[r][c] = reading;
                all &= ok;
            }
            if (all && matched < 0) matched = r;
        }
        return new Evaluation(matched, truth, readings);
    }

    /** A block's memory between evaluations. {@code matched} -2 means never evaluated. */
    public static final class State {
        public int matched = -2, output;
        public long pulseUntil = -1;
        public boolean toggled;

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
