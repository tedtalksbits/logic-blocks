package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The Fill condition, moves into crafters, and the machines' programs (the self-test runs the machines themselves). */
class MachinesTest {
    @BeforeEach void setUp() { MockBukkit.mock(); }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void comparatorReadsLikeVanilla() {
        assertEquals(0, Engine.comparator(new int[27], ones(27)), "empty");
        assertEquals(1, Engine.comparator(withFirst(27, 1), ones(27)), "one item in a chest: 1");
        int[] full = new int[27]; java.util.Arrays.fill(full, 64);
        int[] max = new int[27]; java.util.Arrays.fill(max, 64);
        assertEquals(15, Engine.comparator(full, max), "full");
        full[26] = 63;
        assertEquals(14, Engine.comparator(full, max), "one short of full is not 15");
        int[] tools = new int[27]; java.util.Arrays.fill(tools, 1);
        assertEquals(15, Engine.comparator(tools, ones(27)), "27 unstackable items fill a box");
        int[] half = new int[27]; for (int i = 0; i < 13; i++) half[i] = 64;
        assertEquals(7, Engine.comparator(half, max), "13 of 27 stacks");
        assertEquals(0, Engine.comparator(new int[0], new int[0]));
    }

    static int[] ones(int n) { int[] a = new int[n]; java.util.Arrays.fill(a, 1); return a; }
    static int[] withFirst(int n, int v) { int[] a = new int[n]; a[0] = v; return a; }

    @Test void fillConditions() {
        Condition c = Condition.fill(Side.WEST, Op.EQ, 15);
        assertNull(c.problem());
        assertEquals("fill west = 15", c.describe());
        assertNotNull(Condition.fill(Side.ANY, Op.EQ, 1).problem());
        assertNotNull(Condition.fill(Side.UP, Op.EQ, 16).problem());
        assertNotNull(Condition.fill(Side.UP, Op.IS, 1).problem());
        EngineTest.Fake none = new EngineTest.Fake();
        Program p = new Program(List.of(new Program.Rule(List.of(c), Action.set(1))), Action.set(0));
        assertEquals(-1, Engine.evaluate(p, none).matched(), "no container: false");
        assertEquals("no container", Engine.evaluate(p, none).readings()[0][0]);
        assertEquals(p, Program.decode(p.encode()));

        int[] fills = Snapshot.noFills();
        fills[Side.WEST.ordinal()] = 15;
        Snapshot snap = new Snapshot(new String[]{"air", "air", "air", "air", "air", "air"}, new int[6],
            Collections.nCopies(6, (Map<String, Integer>) null), new int[0], 0, -1).withFills(fills);
        Snapshot back = Snapshot.decode(new Wire.Reader(snap.encode()));
        assertEquals(15, back.fill(Side.WEST));
        assertEquals(-1, back.fill(Side.EAST));
        assertEquals(0, Engine.evaluate(p, back).matched(), "the editor evaluates Fill live");
        assertEquals("fill 15", Engine.evaluate(p, back).readings()[0][0]);
    }

    @Test void crafterSlotsFillLikeAHopperFillsThem() {
        ItemStack[] grid = new ItemStack[9];
        boolean[] off = new boolean[9];
        ItemStack ingot = new ItemStack(Material.IRON_INGOT);
        for (int i = 0; i < 9; i++) {
            int s = Mover.crafterSlot(grid, off, ingot);
            assertEquals(i, s, "empty slots first, in order");
            grid[s] = ingot.clone();
        }
        assertEquals(0, Mover.crafterSlot(grid, off, ingot), "all have 1: the first again");
        grid[0].setAmount(2);
        assertEquals(1, Mover.crafterSlot(grid, off, ingot), "evenly: the next slot with fewer");

        // Rockets: two slots enabled; gunpowder and paper each keep to their own slot.
        ItemStack[] r = new ItemStack[9];
        boolean[] two = new boolean[9];
        for (int i = 2; i < 9; i++) two[i] = true;
        ItemStack gp = new ItemStack(Material.GUNPOWDER), paper = new ItemStack(Material.PAPER);
        assertEquals(0, Mover.crafterSlot(r, two, gp));
        r[0] = gp.clone();
        assertEquals(1, Mover.crafterSlot(r, two, gp), "a second gunpowder takes the empty slot first");
        assertEquals(1, Mover.crafterSlot(r, two, paper));
        r[1] = paper.clone();
        assertEquals(0, Mover.crafterSlot(r, two, gp), "then stacks on its own kind");
        assertEquals(-1, Mover.crafterSlot(r, new boolean[]{true, true, true, true, true, true, true, true, true}, gp), "all disabled");
    }

    @Test void everyMachineProgramIsValid() {
        for (Program p : List.of(Machines.ironCrafter(), Machines.rocketCrafter(1), Machines.rocketCrafter(3), Machines.smelterInput(), Machines.smelterService(),
                Machines.shulkerBrain(true), Machines.shulkerBrain(false), Machines.shulkerFire(Side.DOWN, Machines.BOX_MISSING),
                Machines.shulkerFire(Side.NORTH, Machines.BOX_DONE))) {
            assertNull(p.problem(), p.describe().toString());
            assertEquals(p, Program.decode(p.encode()));
        }
        for (Machines.Kind k : Machines.Kind.values()) assertSame(k, Machines.Kind.of(k.command));
    }

    @Test void craftTriggerGivesARisingEdgeEveryHalfSecondWhileTheGridIsFull() {
        Program p = Machines.ironCrafter();
        Engine.State s = new Engine.State();
        EngineTest.Fake full = new EngineTest.Fake();
        full.fills.put(Side.EAST, 9);
        int rises = 0, last = 0;
        for (long t = 0; t < 40; t += 2) {
            Engine.run(p, full, s, t);
            if (s.output > 0 && last == 0) rises++;
            last = s.output;
        }
        assertEquals(4, rises, "at 0, 10, 20 and 30 ticks");
    }
}
