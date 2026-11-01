package dev.whizzle.logic;

import dev.whizzle.logic.model.Program;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

/** Middle-click on a Logic Block picks a Logic Block, never a plain daylight detector; and items keep "when" rules. */
class PickBlockTest {
    ServerMock server; WorldMock world; LogicBlocksPlugin plugin; PlayerMock player; Block block;
    final Program station = Demos.moveStation(Material.DIAMOND);

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("pick");
        plugin = MockBukkit.load(LogicBlocksPlugin.class);
        player = server.addPlayer("Picker");
        player.teleport(new Location(world, 0.5, 70, 0.5));
        world.loadChunk(0, 0);
        block = world.getBlockAt(2, 70, 2);
        block.setType(LogicItem.BLOCK);
        plugin.manager().create(block, station, player);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    /** The event as the game would send it for a daylight detector: source slot where a plain one is, or -1. */
    PlayerPickBlockEvent pick(boolean ctrl) {
        int plain = player.getInventory().first(Material.DAYLIGHT_DETECTOR);
        PlayerPickBlockEvent e = new PlayerPickBlockEvent(player, block, new ItemStack(Material.DAYLIGHT_DETECTOR), ctrl, 0, plain);
        server.getPluginManager().callEvent(e);
        return e;
    }

    @Test void picksTheLogicBlockWithTheSameRulesOverAPlainDetector() {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(10, new ItemStack(Material.DAYLIGHT_DETECTOR));
        player.getInventory().setItem(20, LogicItem.create(Program.EMPTY));
        player.getInventory().setItem(30, LogicItem.create(station));
        PlayerPickBlockEvent e = pick(false);
        assertFalse(e.isCancelled());
        assertEquals(30, e.getSourceSlot(), "same rules first");
        player.getInventory().setItem(30, null);
        assertEquals(20, pick(false).getSourceSlot(), "else any Logic Block");
    }

    @Test void survivalWithOnlyPlainDetectorsPicksNothing() {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(10, new ItemStack(Material.DAYLIGHT_DETECTOR));
        assertTrue(pick(false).isCancelled());
        assertEquals(Material.DAYLIGHT_DETECTOR, player.getInventory().getItem(10).getType(), "inventory untouched");
    }

    @Test void creativeGetsABlankLogicBlockOrWithCtrlACopyOfTheRules() {
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItem(0, new ItemStack(Material.STONE));
        assertTrue(pick(false).isCancelled(), "the game's plain detector is replaced");
        ItemStack got = player.getInventory().getItem(1);
        assertTrue(LogicItem.is(got), "first empty hotbar slot");
        assertEquals(Program.EMPTY, LogicItem.program(got));
        assertEquals(1, player.getInventory().getHeldItemSlot());
        player.getInventory().setItem(1, null);
        player.getInventory().setHeldItemSlot(1);
        pick(true);
        assertEquals(station, LogicItem.program(player.getInventory().getItem(1)), "Ctrl copies the block's rules");
    }

    @Test void itemsKeepRulesThatAreOnlyOnTheWhenTab() {
        ItemStack item = LogicItem.create(station);
        assertEquals(station, LogicItem.program(item), "a sorter station broken and placed again keeps its rule");
    }

    @Test void plainDetectorsAreLeftAlone() {
        Block plain = world.getBlockAt(5, 70, 5);
        plain.setType(Material.DAYLIGHT_DETECTOR);
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(10, new ItemStack(Material.DAYLIGHT_DETECTOR));
        player.getInventory().setItem(20, LogicItem.create(station));
        PlayerPickBlockEvent e = new PlayerPickBlockEvent(player, plain, new ItemStack(Material.DAYLIGHT_DETECTOR), false, 0, 10);
        server.getPluginManager().callEvent(e);
        assertFalse(e.isCancelled());
        assertEquals(10, e.getSourceSlot(), "a real daylight detector picks a real one");
    }
}
