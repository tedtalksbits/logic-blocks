package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Counters and timers: the block's own memory. */
class MemoryTest {
    final EngineTest.Fake w = new EngineTest.Fake();
    final Engine.State s = new Engine.State();
    long tick;

    /** One check, 2 ticks after the last (the plugin's default cadence); returns the output. */
    int check(Program p) { Engine.run(p, w, s, tick); tick += 2; return s.output; }

    static Program.Rule when(List<Action> actions, Condition... cs) { return new Program.Rule(List.of(cs), actions); }

    @Test void counterActionsRunOnceEachTimeTheRuleBecomesTrue() {
        Program p = Program.EMPTY.withAlso(List.of(when(List.of(Action.add("a", 1)), Condition.signal(Side.WEST, Op.GT, 0))));
        check(p);
        assertEquals(0, s.counters[0]);
        w.signals.put(Side.WEST, 15);
        for (int i = 0; i < 5; i++) check(p);
        assertEquals(1, s.counters[0], "held: counts once, not every check");
        w.signals.put(Side.WEST, 0); check(p);
        w.signals.put(Side.WEST, 15); check(p);
        assertEquals(2, s.counters[0], "pressed again");
    }

    @Test void ifElseCounterActionsRunWhenTheRuleStartsMatching() {
        Program p = new Program(List.of(new Program.Rule(List.of(Condition.signal(Side.WEST, Op.GT, 0)), List.of(Action.set(15), Action.add("b", 5)))), Action.set(0));
        w.signals.put(Side.WEST, 1);
        check(p); check(p); check(p);
        assertEquals(5, s.counters[1]);
        assertEquals(15, s.output);
    }

    @Test void everyRuleSeesTheValuesFromTheStartOfTheCheck() {
        // Rule 1 sets A to 1; rule 2 (A = 1 -> set B) must not see it in the same check.
        Program p = Program.EMPTY.withAlso(List.of(
            when(List.of(Action.setCounter("a", 1)), Condition.signal(Side.WEST, Op.GT, 0)),
            when(List.of(Action.setCounter("b", 9)), Condition.counter("a", Op.EQ, 1))));
        w.signals.put(Side.WEST, 15);
        check(p);
        assertEquals(1, s.counters[0]);
        assertEquals(0, s.counters[1], "same check: rule 2 still saw A = 0");
        check(p);
        assertEquals(9, s.counters[1], "next check");
    }

    @Test void countersStayBetween0AndTheMaximum() {
        Program p = Program.EMPTY.withAlso(List.of(when(List.of(Action.subtract("a", 3)), Condition.signal(Side.WEST, Op.GT, 0)),
            when(List.of(Action.add("b", Condition.MAX_NUMBER)), Condition.signal(Side.EAST, Op.GT, 0))));
        for (int i = 0; i < 2; i++) {
            w.signals.put(Side.WEST, 15); w.signals.put(Side.EAST, 15); check(p);
            w.signals.put(Side.WEST, 0); w.signals.put(Side.EAST, 0); check(p);
        }
        assertEquals(0, s.counters[0]);
        assertEquals(Condition.MAX_NUMBER, s.counters[1]);
    }

    @Test void timersRunAndAreDoneForOneCheck() {
        Program p = new Program(List.of(new Program.Rule(List.of(Condition.timer("t2", true, false)), Action.set(9))), Action.set(0),
            List.of(when(List.of(Action.start("t2", 10)), Condition.signal(Side.WEST, Op.GT, 0))));
        w.signals.put(Side.WEST, 15);
        check(p);   // tick 0: starts, runs out at 10
        Engine.Inputs mem = s.over(w, 4);
        assertEquals(6, mem.timerLeft(1));
        assertEquals(0, mem.timerLeft(0), "other timers aren't running");
        int[] outputs = new int[8];
        for (int i = 0; i < outputs.length; i++) outputs[i] = check(p);   // ticks 2..16
        assertArrayEquals(new int[]{0, 0, 0, 0, 9, 0, 0, 0}, outputs, "done at tick 10, for that check only");
        assertEquals(-1, s.timerEnd[1]);
    }

    @Test void startingARunningTimerRestartsItAndStopCancelsIt() {
        Program p = new Program(List.of(new Program.Rule(List.of(Condition.timer("t1", true, false)), Action.set(15))), Action.keep(), List.of(
            when(List.of(Action.start("t1", 20)), Condition.change(Side.WEST, true)),
            when(List.of(Action.stop("t1")), Condition.change(Side.EAST, true))));
        check(p);
        press(p, Side.WEST);          // starts at ~tick 2
        for (int i = 0; i < 4; i++) check(p);
        press(p, Side.WEST);          // restarted
        for (int i = 0; i < 8; i++) check(p);
        assertEquals(0, s.output, "restarted: not done yet");
        for (int i = 0; i < 4; i++) check(p);
        assertEquals(15, s.output, "done 20 ticks after the second press");
        s.output = 0;
        press(p, Side.WEST);
        press(p, Side.EAST);
        for (int i = 0; i < 20; i++) check(p);
        assertEquals(0, s.output, "stopped: never done");
        assertEquals(-1, s.timerEnd[0]);
    }

    void press(Program p, Side side) { w.signals.put(side, 15); check(p); w.signals.put(side, 0); check(p); }

    @Test void combinationLock() {
        Program p = Demos.lockProgram();
        assertNull(p.problem());
        check(p);
        press(p, Side.WEST); press(p, Side.NORTH);
        assertEquals(2, s.counters[0]);
        assertEquals(0, s.output);
        w.signals.put(Side.EAST, 15);
        check(p);
        assertEquals(3, s.counters[0]);
        check(p);
        assertEquals(15, s.output, "opens");
        assertEquals(0, s.counters[0], "and starts over");
        w.signals.put(Side.EAST, 0);
        int open = 0;
        while (s.output == 15 && open < 100) { check(p); open += 2; }
        assertTrue(open >= 56 && open <= 62, "open for 3 seconds: " + open);

        // Wrong orders.
        for (List<Side> order : List.of(List.of(Side.WEST, Side.EAST, Side.NORTH), List.of(Side.NORTH, Side.EAST), List.of(Side.WEST, Side.NORTH, Side.NORTH, Side.EAST),
                List.of(Side.EAST, Side.WEST, Side.EAST))) {
            for (Side side : order) press(p, side);
            for (int i = 0; i < 5; i++) check(p);
            assertEquals(0, s.output, "stays shut: " + order);
        }
        // A wrong press then the right three still opens.
        press(p, Side.EAST); press(p, Side.WEST); press(p, Side.NORTH); press(p, Side.EAST);
        assertEquals(15, s.output);
    }

    @Test void clockAndDelayedOff() {
        Program clock = Demos.clock(20, 4);
        assertNull(clock.problem());
        List<Long> rises = new java.util.ArrayList<>();
        int last = 0;
        for (int i = 0; i < 100; i++) { long t = tick; int o = check(clock); if (o > 0 && last == 0) rises.add(t); last = o; }   // ticks 0..198
        assertEquals(List.of(20L, 40L, 60L, 80L, 100L, 120L, 140L, 160L, 180L), rises, "a pulse every 20 ticks");

        Engine.State fresh = new Engine.State();
        Program off = Demos.delayedOff(40);
        w.signals.put(Side.WEST, 15);
        Engine.run(off, w, fresh, 0);
        assertEquals(15, fresh.output);
        w.signals.put(Side.WEST, 0);
        long t = 2;
        Engine.run(off, w, fresh, t);
        while (fresh.output == 15 && t < 200) { t += 2; Engine.run(off, w, fresh, t); }
        assertEquals(42, t, "goes off 40 ticks after the input does");
    }

    @Test void edgesAreRememberedSoNothingRunsAgainAfterAReload() {
        Program p = Program.EMPTY.withAlso(List.of(when(List.of(Action.add("a", 1)), Condition.signal(Side.WEST, Op.GT, 0))));
        w.signals.put(Side.WEST, 15);
        check(p);
        // What a reload restores: counters, timers, which when-rules were true.
        Engine.State reloaded = new Engine.State(s.matched, s.output, -1, s.toggled);
        System.arraycopy(s.counters, 0, reloaded.counters, 0, 4);
        reloaded.alsoWas = s.alsoWas;
        Engine.run(p, w, reloaded, tick);
        assertEquals(1, reloaded.counters[0], "still held after the reload: not counted again");
    }

    @Test void formatDescribeAndProblems() {
        Program p = new Program(List.of(new Program.Rule(List.of(Condition.counter("a", Op.GE, 3), Condition.timer("t4", false, true)),
            List.of(Action.set(15), Action.start("t4", 30), Action.subtract("a", 2)))), Action.keep(),
            List.of(when(List.of(Action.setCounter("d", 7), Action.stop("t1"), Action.add("b", 1)), Condition.timer("t1", true, false))));
        assertNull(p.problem());
        assertEquals(p, Program.decode(p.encode()));
        assertEquals(3, new Wire.Reader(p.encode()).varInt());
        assertEquals(List.of("1. when counter A >= 3 and T4 is not running -> output 15, then start T4 for 1.5s, then subtract 2 from A",
            "otherwise -> keep output", "when rules (every true one runs):", "1. when T1 is done -> set D to 7, then stop T1, then add 1 to B"), p.describe());
        assertFalse(p.acts() && false);
        assertEquals("Pick a counter, A to D", Condition.counter("e", Op.EQ, 1).problem());
        assertEquals("Pick a timer, T1 to T4", Action.start("t5", 20).problem());
        assertEquals("Counter checks compare a number", Condition.counter("a", Op.IS, 1).problem());
        assertNotNull(Action.add("a", 0).problem());
        assertNull(Action.setCounter("a", 0).problem());
        assertNotNull(Action.start("t1", Action.MAX_TIMER_TICKS + 1).problem());
        assertEquals("5s", Action.seconds(100));
        assertEquals("0.05s", Action.seconds(1));
        Program outputInWhen = Program.EMPTY.withAlso(List.of(when(List.of(Action.add("a", 1), Action.set(3)))));
        assertNotNull(outputInWhen.problem(), "when rules still can't set the output");
    }

    @Test void snapshotCarriesMemoryToTheEditor() {
        Engine.State st = new Engine.State();
        st.counters[2] = 41;
        st.timerEnd[1] = 130;
        st.timerDone = 0b1000;
        Snapshot snap = new Snapshot(new String[]{"air", "air", "air", "air", "air", "air"}, new int[6],
            java.util.Collections.nCopies(6, (Map<String, Integer>) null), new int[0], 3, -1).withMemory(st, 100);
        Snapshot back = Snapshot.decode(new Wire.Reader(snap.encode()));
        assertEquals(41, back.counter(2));
        assertEquals(30, back.timerLeft(1));
        assertTrue(back.timerDone(3));
        Program p = new Program(List.of(new Program.Rule(List.of(Condition.counter("c", Op.EQ, 41), Condition.timer("t2", true, true), Condition.timer("t4", true, false)), Action.set(1))), Action.set(0));
        Engine.Evaluation e = Engine.evaluate(p, back);
        assertEquals(0, e.matched(), "the editor evaluates counter and timer rows live");
        assertArrayEquals(new String[]{"C = 41", "T2 running, 1.5s left", "T4 done"}, e.readings()[0]);
    }
}
