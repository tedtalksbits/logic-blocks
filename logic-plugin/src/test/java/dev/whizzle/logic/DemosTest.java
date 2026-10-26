package dev.whizzle.logic;

import dev.whizzle.logic.model.Program;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** /logic demo sorter: what it builds and when it refuses (the redstone itself is checked by the real-Paper self-test). */
class DemosTest {
    ServerMock server; WorldMock world; LogicBlocksPlugin plugin; PlayerMock op;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("demo");
        plugin = MockBukkit.load(LogicBlocksPlugin.class);
        op = server.addPlayer("Op");
        op.setOp(true);
        op.setGameMode(GameMode.CREATIVE);
        op.teleport(new Location(world, 0.5, 70, 0.5));
        for (int cx = -1; cx <= 1; cx++) for (int cz = -1; cz <= 1; cz++) world.loadChunk(cx, cz);
        for (int x = -2; x <= 16; x++) for (int z = -2; z <= 8; z++) for (int y = 69; y <= 76; y++) world.getBlockAt(x, y, z).setType(Material.AIR);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    String lastMessage() { String m, last = null; while ((m = op.nextMessage()) != null) last = m; return last; }

    @Test void buildsAProgrammedStockedSorterBesideThePlayer() {
        op.performCommand("logic demo sorter diamond emerald");
        String reply = lastMessage();
        assertTrue(reply != null && reply.contains("Built a sorter"), "confirmation: " + reply);
        // Feet at (0, 70, 0): line from x 2, z 3; chests at y 70, line hoppers at y 73.
        for (int i = 0; i < 2; i++) {
            assertEquals(Material.CHEST, world.getBlockAt(2 + i, 70, 3).getType());
            for (int y = 71; y <= 73; y++) assertEquals(Material.HOPPER, world.getBlockAt(2 + i, y, 3).getType());
            assertEquals(Material.GLASS, world.getBlockAt(2 + i, 74, 3).getType());
        }
        LogicManager m = plugin.manager();
        assertEquals(Demos.stationReader(Material.DIAMOND), m.at(world.getBlockAt(2, 73, 2)).orElseThrow().program());
        assertEquals(Demos.stationGate(Material.DIAMOND), m.at(world.getBlockAt(2, 72, 2)).orElseThrow().program());
        assertEquals(Demos.stationReader(Material.EMERALD), m.at(world.getBlockAt(3, 73, 2)).orElseThrow().program());
        assertEquals("Op", m.at(world.getBlockAt(3, 72, 2)).orElseThrow().ownerName(), "the player owns them, so they can edit");
        assertEquals(4, m.nodes().size());
        assertEquals(Material.CHEST, world.getBlockAt(4, 73, 3).getType(), "overflow");
        Block feeder = world.getBlockAt(1, 74, 3);
        assertEquals(Material.CHEST, feeder.getType());
        var stock = Demos.inventory(feeder);
        assertTrue(stock.contains(Material.DIAMOND) && stock.contains(Material.EMERALD) && stock.contains(Material.STONE), "stocked with a mix");
    }

    @Test void defaultsToDiamondEmeraldSword() {
        op.performCommand("logic demo sorter");
        assertEquals(6, plugin.manager().nodes().size());
        assertEquals(Demos.stationGate(Material.DIAMOND_SWORD), plugin.manager().at(world.getBlockAt(4, 72, 2)).orElseThrow().program());
        assertEquals(new Program(Demos.stationGate(Material.DIAMOND_SWORD).rules(), Demos.stationGate(Material.DIAMOND_SWORD).otherwise()),
            Demos.stationGate(Material.DIAMOND_SWORD));
    }

    @Test void refusesWhenSomethingIsInTheWay() {
        world.getBlockAt(3, 72, 2).setType(Material.STONE);
        op.performCommand("logic demo sorter diamond emerald");
        assertTrue(lastMessage().contains("Not enough room: stone at 3 72 2"));
        assertEquals(0, plugin.manager().nodes().size(), "nothing built");
        assertEquals(Material.AIR, world.getBlockAt(2, 70, 3).getType());
    }

    @Test void rejectsUnknownItemsAndNonOps() {
        op.performCommand("logic demo sorter diamond notanitem");
        assertTrue(lastMessage().contains("Not an item: notanitem"));
        PlayerMock guest = server.addPlayer("Guest");
        guest.performCommand("logic demo sorter");
        assertEquals(0, plugin.manager().nodes().size());
        assertTrue(plugin.onTabComplete(op, null, "logic", new String[]{"demo", "sorter", "diamond_sw"}).contains("diamond_sword"));
        assertEquals(List.of("info"), plugin.onTabComplete(guest, null, "logic", new String[]{""}));
    }

    @Test void stationRulesMatchTheGuide() {
        assertEquals(List.of("1. when diamond south >= 1 and items south <= 1 and signal down != 7 -> output 15", "otherwise -> output 0"),
            Demos.stationReader(Material.DIAMOND).describe().stream().map(String::trim).toList());
        assertTrue(Demos.stationGate(Material.EGG).describe().getFirst().contains("80"), "16-stack items: full at 80");
        assertTrue(Demos.stationGate(Material.DIAMOND_SWORD).describe().getFirst().contains(">= 5"), "unstackables: full at 5");
    }
}
