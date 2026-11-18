package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Wildcards in block and item names: deepslate_*, *_ore, *deepslate*, *. */
class IdPatternTest {
    @Test void matching() {
        assertTrue(IdPattern.matches("deepslate_*", "deepslate_bricks"));
        assertTrue(IdPattern.matches("deepslate_*", "deepslate_tile_slab"));
        assertFalse(IdPattern.matches("deepslate_*", "deepslate"), "the underscore must be there");
        assertFalse(IdPattern.matches("deepslate_*", "cobbled_deepslate"));
        assertTrue(IdPattern.matches("*deepslate*", "cobbled_deepslate"));
        assertTrue(IdPattern.matches("*deepslate*", "deepslate"));
        assertTrue(IdPattern.matches("*_ore", "deepslate_iron_ore"));
        assertFalse(IdPattern.matches("*_ore", "raw_iron"));
        assertTrue(IdPattern.matches("*", "anything"));
        assertTrue(IdPattern.matches("a*b*c", "a_x_b_y_c"));
        assertFalse(IdPattern.matches("a*b*c", "a_x_c_y_b"));
        assertTrue(IdPattern.matches("red_wool", "red_wool"), "no star: exact");
        assertFalse(IdPattern.matches("red_wool", "red_wool_x"));
        assertTrue(IdPattern.valid("deepslate_*"));
        assertFalse(IdPattern.valid("Deepslate*"));
        assertFalse(IdPattern.valid(""));
        assertEquals(17, IdPattern.count(Map.of("deepslate_bricks", 10, "deepslate_tiles", 7, "cobblestone", 64), "deepslate_*"));
        assertEquals(0, IdPattern.count(Map.of("cobblestone", 64), "deepslate_*"));
    }

    @Test void exclusionsAndTags() {
        IdPattern.Tags before = IdPattern.tags;
        try {
            IdPattern.tags = (block, tag, id) -> switch (tag) {
                case "minecraft:slabs" -> id.endsWith("_slab");
                case "minecraft:logs" -> block ? id.endsWith("_log") : id.endsWith("_log") || id.endsWith("_wood");
                default -> false;
            };
            String stone = "*stone*, !*cobblestone*";
            assertTrue(IdPattern.valid(stone));
            assertTrue(IdPattern.matches(stone, "stone"));
            assertTrue(IdPattern.matches(stone, "smooth_stone"));
            assertFalse(IdPattern.matches(stone, "cobblestone"));
            assertFalse(IdPattern.matches(stone, "mossy_cobblestone"));
            assertFalse(IdPattern.matches(stone, "dirt"));
            assertTrue(IdPattern.matches("!cobblestone", "dirt"), "only exclusions: anything else");
            assertFalse(IdPattern.matches("!cobblestone", "cobblestone"));
            assertTrue(IdPattern.matches("#slabs", "oak_slab"));
            assertTrue(IdPattern.matches("#minecraft:slabs", "stone_slab"));
            assertFalse(IdPattern.matches("#slabs", "oak_planks"));
            assertTrue(IdPattern.matches("#slabs, !*oak*", "stone_slab"));
            assertFalse(IdPattern.matches("#slabs, !*oak*", "oak_slab"));
            assertTrue(IdPattern.matches("#slabs, #logs", "oak_log"), "several tags");
            assertTrue(IdPattern.matches("#logs", "oak_wood", false), "item tags for items");
            assertFalse(IdPattern.matches("#logs", "oak_wood", true), "block tags for blocks");
            assertEquals(List.of("minecraft:slabs", "minecraft:logs"), IdPattern.tagsIn("#slabs, !#logs"));
            assertEquals(5, IdPattern.count(Map.of("oak_slab", 2, "stone_slab", 3, "oak_planks", 9), "#slabs"));
            for (String bad : List.of("#", "#Slabs", "a,,b", "!", ",", "#slabs, !", "stone, Dirt"))
                assertFalse(IdPattern.valid(bad), bad);
            assertTrue(IdPattern.valid("#minecraft:logs, oak_planks, !*stripped*"));
            assertFalse(IdPattern.valid("x".repeat(IdPattern.MAX + 1)));
            assertNull(Condition.items(Side.UP, stone, Op.GE, 1).problem());
            assertNull(Action.move("#slabs, !*oak*", Side.UP, Side.DOWN).problem());
            assertNull(Condition.block(Side.NORTH, true, "#logs").problem());
        } finally { IdPattern.tags = before; }
    }

    @Test void blockAndItemsConditionsAndMovesTakePatterns() {
        Condition block = Condition.block(Side.NORTH, true, "deepslate_*");
        Condition items = Condition.items(Side.UP, "*_ore", Op.GE, 3);
        Action move = Action.move("*deepslate*", Side.UP, Side.DOWN);
        for (Object o : List.of(block, items)) assertNull(((Condition) o).problem(), o.toString());
        assertNull(move.problem());
        Program p = new Program(List.of(new Program.Rule(List.of(block), Action.set(15)), new Program.Rule(List.of(items), Action.set(7))), Action.set(0),
            List.of(new Program.Rule(List.of(), move)));
        assertNull(p.problem());
        assertEquals(p, Program.decode(p.encode()));

        EngineTest.Fake w = new EngineTest.Fake();
        w.blocks.put(Side.NORTH, "deepslate_tiles");
        assertEquals(0, Engine.evaluate(p, w).matched());
        w.blocks.put(Side.NORTH, "stone");
        assertEquals(-1, Engine.evaluate(p, w).matched(), "no container up: items is false too");
        Condition notDeepslate = Condition.block(Side.NORTH, false, "deepslate_*");
        assertEquals(0, Engine.evaluate(new Program(List.of(new Program.Rule(List.of(notDeepslate), Action.set(1))), Action.set(0)), w).matched(), "is not");

        Snapshot snap = new Snapshot(new String[]{"air", "air", "air", "air", "air", "air"}, new int[6],
            java.util.Arrays.asList(null, null, null, null, Map.of("iron_ore", 2, "deepslate_gold_ore", 1, "coal", 9), null), new int[0], 0, -1);
        assertEquals(3, snap.items(Side.UP, "*_ore"), "the editor counts by pattern too");
        assertNotNull(Condition.block(Side.NORTH, true, "Not Valid").problem());
    }
}
