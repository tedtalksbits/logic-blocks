package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** v2 rules: "also" rules, several actions per rule, turns on/off, move, and reading v1 programs. */
class RulesV2Test {
    static Program.Rule rule(List<Action> actions, Condition... cs) { return new Program.Rule(List.of(cs), actions); }

    /** A v1 program's bytes exactly as v1 wrote them (format 1, one action per rule as kind, strength, ticks). */
    static byte[] v1Bytes() {
        Wire.Writer out = new Wire.Writer().varInt(1).varInt(2);
        out.varInt(1).varInt(Condition.Kind.BLOCK.ordinal()).varInt(Side.NORTH.ordinal()).varInt(Op.IS.ordinal()).utf("red_wool").varInt(0).varInt(0);
        out.varInt(Action.Kind.TOGGLE.ordinal()).varInt(15).varInt(0);
        out.varInt(1).varInt(Condition.Kind.SIGNAL.ordinal()).varInt(Side.WEST.ordinal()).varInt(Op.GE.ordinal()).utf("").varInt(3).varInt(0);
        out.varInt(Action.Kind.PULSE.ordinal()).varInt(9).varInt(40);
        out.varInt(Action.Kind.KEEP.ordinal()).varInt(0).varInt(0);
        return out.bytes();
    }

    @Test void v1ProgramsStillLoadAndSaveAsV2() {
        Program old = Program.decode(v1Bytes());
        assertEquals(new Program(List.of(
            new Program.Rule(List.of(Condition.block(Side.NORTH, true, "red_wool")), Action.toggle(15)),
            new Program.Rule(List.of(Condition.signal(Side.WEST, Op.GE, 3)), Action.pulse(9, 40))), Action.keep()), old);
        assertTrue(old.also().isEmpty());
        assertNull(old.problem());
        byte[] saved = old.encode();
        assertEquals(Program.FORMAT, new Wire.Reader(saved).varInt(), "re-saved in the new format");
        assertEquals(old, Program.decode(saved));
    }

    @Test void v2RoundTripWithEverything() {
        Program p = new Program(
            List.of(rule(List.of(Action.toggle(15), Action.move("coal", Side.NORTH, Side.DOWN)), Condition.change(Side.WEST, true))),
            Action.keep(),
            List.of(rule(List.of(Action.move("*", Side.UP, Side.DOWN))),
                rule(List.of(Action.move("diamond", Side.EAST, Side.SOUTH)), Condition.items(Side.EAST, "diamond", Op.GE, 1), Condition.change(Side.ANY, false))));
        assertNull(p.problem());
        assertEquals(p, Program.decode(p.encode()));
        assertTrue(p.acts());
        assertFalse(Program.EMPTY.acts());
        assertThrows(IllegalArgumentException.class, () -> Program.decode(new Wire.Writer().varInt(3).bytes()), "a newer format is refused, not misread");
    }

    @Test void problemsNameTheRuleAndSayWhatToDo() {
        Program outputFirst = new Program(List.of(rule(List.of(Action.move("coal", Side.UP, Side.DOWN)))), Action.set(0));
        assertTrue(outputFirst.problem().startsWith("Rule 1: the first action sets the output"), outputFirst.problem());
        Program twoOutputs = new Program(List.of(rule(List.of(Action.set(1), Action.set(2)))), Action.set(0));
        assertTrue(twoOutputs.problem().contains("only the first action sets the output"));
        Program alsoOutput = Program.EMPTY.withAlso(List.of(rule(List.of(Action.set(3)))));
        assertTrue(alsoOutput.problem().startsWith("Also 1: \"also\" rules don't set the output"), alsoOutput.problem());
        Program sameSide = Program.EMPTY.withAlso(List.of(rule(List.of(Action.move("coal", Side.UP, Side.UP)))));
        assertTrue(sameSide.problem().contains("different side"));
        Program noSide = Program.EMPTY.withAlso(List.of(rule(List.of(Action.move("coal", Side.ANY, Side.UP)))));
        assertTrue(noSide.problem().contains("Pick the sides"));
        Program badItem = Program.EMPTY.withAlso(List.of(rule(List.of(Action.move("Not An Item!", Side.UP, Side.DOWN)))));
        assertTrue(badItem.problem().contains("Name an item to move"));
        Program fourActions = Program.EMPTY.withAlso(List.of(rule(List.of(Action.move("a", Side.UP, Side.DOWN), Action.move("b", Side.UP, Side.DOWN),
            Action.move("c", Side.UP, Side.DOWN), Action.move("d", Side.UP, Side.DOWN)))));
        assertTrue(fourActions.problem().contains("at most 3 actions"));
        assertThrows(IllegalArgumentException.class, () -> new Program.Rule(List.of(), List.of()), "a rule does something");
    }

    @Test void turnsOnAndOffFireOnceAndNotAtTheFirstCheck() {
        Program p = new Program(List.of(rule(List.of(Action.toggle(15)), Condition.change(Side.WEST, true))), Action.keep(),
            List.of(rule(List.of(Action.move("*", Side.UP, Side.DOWN)), Condition.change(Side.WEST, false))));
        EngineTest.Fake w = new EngineTest.Fake();
        Engine.State s = new Engine.State();
        w.signals.put(Side.WEST, 15);
        Engine.Evaluation e = Engine.evaluate(p, w, s.signals);
        assertEquals(-1, e.matched(), "already on at the first check: that's not turning on");
        Engine.remember(s.signals, e);
        Engine.step(p, s, e.matched(), 0);
        w.signals.put(Side.WEST, 0);
        e = Engine.evaluate(p, w, s.signals);
        assertTrue(e.alsoTrue()[0], "turned off");
        assertEquals(List.of(Action.move("*", Side.UP, Side.DOWN)), Engine.worldActions(p, e));
        Engine.remember(s.signals, e);
        w.signals.put(Side.WEST, 4);
        e = Engine.evaluate(p, w, s.signals);
        assertEquals(0, e.matched(), "turned on");
        assertEquals("signal 0 -> 4", e.readings()[0][0]);
        Engine.remember(s.signals, e);
        assertEquals(15, Engine.step(p, s, e.matched(), 2));
        e = Engine.evaluate(p, w, s.signals);
        assertEquals(-1, e.matched(), "still on: true for one check only, a held button counts once");
        assertFalse(e.alsoTrue()[0]);
        Engine.remember(s.signals, e);
        assertEquals(15, Engine.step(p, s, e.matched(), 4), "otherwise keep: the toggle stays");
        w.signals.put(Side.WEST, 9);
        e = Engine.evaluate(p, w, s.signals);
        assertEquals(-1, e.matched(), "4 -> 9 isn't turning on");
    }

    @Test void worldActionsComeFromTheMatchedOutputRuleThenEachTrueAlsoRule() {
        Action feed = Action.move("coal", Side.NORTH, Side.DOWN), sort = Action.move("diamond", Side.UP, Side.DOWN), dump = Action.move("*", Side.UP, Side.EAST);
        Program p = new Program(
            List.of(rule(List.of(Action.set(15), feed), Condition.signal(Side.WEST, Op.GT, 0)), rule(List.of(Action.set(3), dump))),
            Action.set(0),
            List.of(rule(List.of(sort)), rule(List.of(dump), Condition.block(Side.SOUTH, true, "stone"))));
        EngineTest.Fake w = new EngineTest.Fake();
        w.signals.put(Side.WEST, 5);
        Engine.Evaluation e = Engine.evaluate(p, w);
        assertEquals(0, e.matched());
        assertEquals(List.of(feed, sort), Engine.worldActions(p, e), "only the matched output rule's extras, then true also rules");
        assertEquals(2 + 2, e.truth().length, "output rules then also rules");
        w.signals.put(Side.WEST, 0);
        w.blocks.put(Side.SOUTH, "stone");
        e = Engine.evaluate(p, w);
        assertEquals(1, e.matched(), "a rule with no conditions always holds");
        assertEquals(List.of(dump, sort, dump), Engine.worldActions(p, e));
    }

    @Test void describeReadsLikeSentences() {
        Program p = new Program(List.of(rule(List.of(Action.toggle(15), Action.move("coal", Side.NORTH, Side.DOWN)), Condition.change(Side.WEST, true))),
            Action.keep(), List.of(rule(List.of(Action.move("diamond", Side.UP, Side.DOWN)))));
        assertEquals(List.of(
            "1. when signal west turns on -> toggle 15, then move coal from north to down",
            "otherwise -> keep output",
            "also 1. always -> move diamond from up to down"), p.describe());
    }
}
