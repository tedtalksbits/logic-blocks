package dev.whizzle.logic.client;

import dev.whizzle.logic.client.ui.IdCompletion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class IdCompletionTest {
    final IdCompletion ids = new IdCompletion(List.of("minecraft:diamond_axe", "minecraft:diamond", "minecraft:dirt",
        "minecraft:diamond_block", "create:wrench"));

    @Test void shortestMatchFirstWithoutTheVanillaNamespace() {
        assertEquals("diamond", ids.complete("dia"));
        assertEquals("mond", ids.suggestion("dia"));
        assertEquals("dirt", ids.complete("dir"));
        assertEquals("diamond_axe", ids.complete("diamond_"));
        assertEquals("diamond", ids.complete("minecraft:diam"), "the namespace may be typed");
        assertEquals("diamond", ids.complete("DIA"));
        assertEquals("create:wrench", ids.complete("cre"), "modded ids keep their namespace");
    }

    @Test void nothingToOffer() {
        assertNull(ids.complete(""));
        assertNull(ids.complete("*"), "* means any item");
        assertNull(ids.complete("diamond"), "already a whole id: Tab moves on");
        assertNull(ids.suggestion("stone"));
        assertTrue(ids.known("minecraft:dirt"));
        assertFalse(ids.known("dia"));
    }
}
