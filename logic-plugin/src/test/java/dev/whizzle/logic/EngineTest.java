package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The rule language: first match wins, each action kind, and the program format. */
class EngineTest {
    /** A world you can edit by hand. */
    static final class Fake implements Engine.Inputs {
        final Map<Side, String> blocks = new EnumMap<>(Side.class);
        final Map<Side, Integer> signals = new EnumMap<>(Side.class);
        final Map<Side, Map<String, Integer>> containers = new EnumMap<>(Side.class);
        int[] players = {};
        public String block(Side s) { return blocks.getOrDefault(s, "air"); }
        public int signal(Side s) { return s == Side.ANY ? signals.values().stream().mapToInt(i -> i).max().orElse(0) : signals.getOrDefault(s, 0); }
        public int items(Side s, String id) {
            Map<String, Integer> c = containers.get(s);
            if (c == null) return -1;
            return id.equals("*") ? c.values().stream().mapToInt(i -> i).sum() : c.getOrDefault(id, 0);
        }
        public int players(int r) { return (int) Arrays.stream(players).filter(d -> d <= r).count(); }
    }

    static Program.Rule rule(Action a, Condition... cs) { return new Program.Rule(List.of(cs), a); }

    @Test void firstMatchingRuleWinsAndOtherwiseCoversTheRest() {
        Program p = new Program(List.of(
            rule(Action.set(15), Condition.block(Side.NORTH, true, "red_wool")),
            rule(Action.set(7), Condition.block(Side.NORTH, true, "blue_wool")),
            rule(Action.set(3), Condition.signal(Side.ANY, Op.GE, 1))), Action.set(0));
        Fake w = new Fake();
        Engine.State s = new Engine.State();
        assertEquals(0, Engine.step(p, s, Engine.evaluate(p, w).matched(), 0));
        w.blocks.put(Side.NORTH, "blue_wool");
        assertEquals(7, Engine.step(p, s, Engine.evaluate(p, w).matched(), 1));
        w.signals.put(Side.WEST, 5);
        assertEquals(7, Engine.step(p, s, Engine.evaluate(p, w).matched(), 2), "rule 2 comes before rule 3");
        w.blocks.put(Side.NORTH, "red_wool");
        assertEquals(15, Engine.step(p, s, Engine.evaluate(p, w).matched(), 3));
        w.blocks.put(Side.NORTH, "stone");
        assertEquals(3, Engine.step(p, s, Engine.evaluate(p, w).matched(), 4));
    }

    @Test void allConditionsInARuleMustHoldAndReadingsAreReported() {
        Program p = new Program(List.of(rule(Action.set(9),
            Condition.items(Side.DOWN, "coal", Op.LT, 10), Condition.players(5, Op.GE, 1))), Action.set(0));
        Fake w = new Fake();
        var e = Engine.evaluate(p, w);
        assertEquals(-1, e.matched());
        assertEquals("no container", e.readings()[0][0]);
        w.containers.put(Side.DOWN, Map.of("coal", 4, "iron_ingot", 30));
        e = Engine.evaluate(p, w);
        assertArrayEquals(new boolean[]{true, false}, e.truth()[0]);
        assertEquals("4", e.readings()[0][0]); assertEquals("0", e.readings()[0][1]);
        w.players = new int[]{3};
        assertEquals(0, Engine.evaluate(p, w).matched());
        assertEquals(34, w.items(Side.DOWN, "*"));
    }

    @Test void pulseFiresOnceWhenTheRuleStartsMatching() {
        Program p = new Program(List.of(rule(Action.pulse(15, 10), Condition.signal(Side.NORTH, Op.GT, 0))), Action.set(0));
        Fake w = new Fake();
        Engine.State s = new Engine.State();
        Engine.step(p, s, Engine.evaluate(p, w).matched(), 0);
        w.signals.put(Side.NORTH, 15);
        assertEquals(15, Engine.step(p, s, Engine.evaluate(p, w).matched(), 100));
        assertEquals(15, Engine.step(p, s, Engine.evaluate(p, w).matched(), 109));
        assertEquals(0, Engine.step(p, s, Engine.evaluate(p, w).matched(), 110), "pulse ends after 10 ticks");
        assertEquals(0, Engine.step(p, s, Engine.evaluate(p, w).matched(), 150), "still held: no new pulse");
        w.signals.put(Side.NORTH, 0);
        Engine.step(p, s, Engine.evaluate(p, w).matched(), 151);
        w.signals.put(Side.NORTH, 3);
        assertEquals(15, Engine.step(p, s, Engine.evaluate(p, w).matched(), 152), "pressed again: new pulse");
    }

    @Test void toggleLatchesWithKeepAsOtherwise() {
        // A button on the north face flips a door: the classic T flip-flop in one rule.
        Program p = new Program(List.of(rule(Action.toggle(15), Condition.signal(Side.NORTH, Op.GT, 0))), Action.keep());
        assertNull(p.problem());
        Fake w = new Fake();
        Engine.State s = new Engine.State();
        assertEquals(0, Engine.step(p, s, Engine.evaluate(p, w).matched(), 0));
        int[] presses = {15, 15, 15};
        int tick = 1;
        int expected = 0;
        for (int ignored : presses) {
            w.signals.put(Side.NORTH, 15);
            expected = expected == 0 ? 15 : 0;
            assertEquals(expected, Engine.step(p, s, Engine.evaluate(p, w).matched(), tick++));
            assertEquals(expected, Engine.step(p, s, Engine.evaluate(p, w).matched(), tick++), "held button doesn't flip again");
            w.signals.put(Side.NORTH, 0);
            assertEquals(expected, Engine.step(p, s, Engine.evaluate(p, w).matched(), tick++), "released: keep");
        }
    }

    @Test void setOverridesARunningPulseAndKeepLetsItFinish() {
        Program p = new Program(List.of(
            rule(Action.set(4), Condition.block(Side.UP, true, "stone")),
            rule(Action.pulse(15, 5), Condition.signal(Side.ANY, Op.GT, 0))), Action.keep());
        Fake w = new Fake();
        Engine.State s = new Engine.State();
        w.signals.put(Side.EAST, 1);
        assertEquals(15, Engine.step(p, s, Engine.evaluate(p, w).matched(), 0));
        w.blocks.put(Side.UP, "stone");
        assertEquals(4, Engine.step(p, s, Engine.evaluate(p, w).matched(), 1));
        assertEquals(4, Engine.step(p, s, Engine.evaluate(p, w).matched(), 10), "pulse cancelled, no late drop to 0");
        w.blocks.remove(Side.UP); w.signals.clear();
        assertEquals(4, Engine.step(p, s, Engine.evaluate(p, w).matched(), 11), "otherwise keep");
    }

    @Test void programsRoundTripAndValidate() {
        Program p = new Program(List.of(
            rule(Action.set(15), Condition.block(Side.NORTH, true, "minecraft:Red_Wool")),
            rule(Action.pulse(8, 20), Condition.items(Side.DOWN, "*", Op.GE, 64), Condition.players(16, Op.EQ, 2)),
            rule(Action.toggle(1))), Action.keep());
        assertNull(p.problem());
        assertEquals("red_wool", p.rules().get(0).conditions().get(0).id(), "ids normalised");
        assertEquals(p, Program.decode(p.encode()));
        assertEquals("1. when north is red_wool → output 15", p.describe().get(0));
        assertEquals("3. always → toggle 1", p.describe().get(2));

        assertNotNull(new Program(List.of(rule(Action.set(16))), Action.set(0)).problem());
        assertNotNull(new Program(List.of(rule(Action.set(1), Condition.signal(Side.NORTH, Op.IS, 3))), Action.set(0)).problem());
        assertNotNull(new Program(List.of(rule(Action.set(1), Condition.block(Side.ANY, true, "stone"))), Action.set(0)).problem());
        assertNotNull(new Program(List.of(rule(Action.set(1), Condition.block(Side.UP, true, "bad id!"))), Action.set(0)).problem());
        assertNotNull(new Program(List.of(rule(Action.pulse(15, 0))), Action.set(0)).problem());
        assertNotNull(new Program(List.of(), Action.toggle(15)).problem(), "otherwise can't toggle");
        List<Program.Rule> nine = Collections.nCopies(9, rule(Action.set(1)));
        assertNotNull(new Program(nine, Action.set(0)).problem());
        assertThrows(IllegalArgumentException.class, () -> Program.decode(new byte[]{9}));
        byte[] good = p.encode();
        assertThrows(IllegalArgumentException.class, () -> Program.decode(Arrays.copyOf(good, good.length - 1)));
    }

    @Test void snapshotEvaluatesTheSameAsTheWorld() {
        Fake w = new Fake();
        w.blocks.put(Side.NORTH, "red_wool"); w.signals.put(Side.WEST, 9);
        w.containers.put(Side.DOWN, Map.of("coal", 12)); w.players = new int[]{2, 9};
        String[] blocks = new String[6]; int[] signals = new int[6];
        List<Map<String, Integer>> containers = new ArrayList<>();
        for (Side s : Side.FACES) {
            blocks[s.ordinal()] = w.block(s); signals[s.ordinal()] = w.signal(s);
            containers.add(w.containers.get(s));
        }
        Snapshot snap = new Snapshot(blocks, signals, containers, new int[]{20, 90}, 7, 1);
        Snapshot back = Snapshot.decode(new Wire.Reader(snap.encode()));
        Program p = new Program(List.of(
            rule(Action.set(1), Condition.block(Side.NORTH, false, "red_wool")),
            rule(Action.set(2), Condition.signal(Side.ANY, Op.EQ, 9), Condition.items(Side.DOWN, "coal", Op.GT, 10), Condition.players(5, Op.EQ, 1))),
            Action.set(0));
        assertEquals(Engine.evaluate(p, w).matched(), Engine.evaluate(p, back).matched());
        assertEquals(1, Engine.evaluate(p, back).matched());
        assertEquals(7, back.output()); assertEquals(1, back.matched());
        assertEquals(-1, back.items(Side.UP, "*"));
        assertEquals(2, back.players(9)); assertEquals(1, back.players(8));
    }
}
