package dev.whizzle.logic;

import dev.whizzle.logic.model.Program;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Middle-click on a Logic Block ends with a Logic Block in hand, never a plain daylight detector. MockBukkit doesn't
 * handle pick packets, so {@link #middleClick} does what Paper 26.3's ServerGamePacketListenerImpl.tryPickItem does
 * (read from its decompiled code): build the event the same way, call it, then apply its slots the same way. The
 * assertions are on what the player ends up holding.
 */
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

    // ---- Paper's pick handling, for the block's clone item (a plain daylight detector) ----

    static boolean hotbar(int slot) { return slot >= 0 && slot < 9; }

    /** Vanilla Inventory.findSlotMatchingItem: same item and same components, so only a plain detector matches. */
    static int findMatching(PlayerInventory inv) {
        for (int i = 0; i < 36; i++) { ItemStack it = inv.getItem(i); if (it != null && it.isSimilar(new ItemStack(Material.DAYLIGHT_DETECTOR))) return i; }
        return -1;
    }

    /** Vanilla Inventory.getSuitableHotbarSlot (simplified: selected if empty, else first empty, else selected). */
    static int suitable(PlayerInventory inv) {
        int selected = inv.getHeldItemSlot();
        ItemStack at = inv.getItem(selected);
        if (at == null || at.getType().isAir()) return selected;
        for (int i = 0; i < 9; i++) { ItemStack it = inv.getItem(i); if (it == null || it.getType().isAir()) return i; }
        return selected;
    }

    PlayerPickBlockEvent middleClick(boolean ctrl) {
        PlayerInventory inv = player.getInventory();
        int source = findMatching(inv);
        int target = hotbar(source) ? source : suitable(inv);
        PlayerPickBlockEvent e = new PlayerPickBlockEvent(player, block, new ItemStack(Material.DAYLIGHT_DETECTOR), ctrl, target, source);
        if (!e.callEvent()) return e;
        int s = e.getSourceSlot();
        if (s != -1) {
            if (hotbar(s) && hotbar(e.getTargetSlot())) inv.setHeldItemSlot(e.getTargetSlot());
            else {   // Inventory.pickSlot: swap source into target and select it
                ItemStack moving = inv.getItem(s), there = inv.getItem(e.getTargetSlot());
                inv.setItem(e.getTargetSlot(), moving);
                inv.setItem(s, there);
                inv.setHeldItemSlot(e.getTargetSlot());
            }
        } else if (player.getGameMode() == GameMode.CREATIVE) {
            inv.setItem(e.getTargetSlot(), new ItemStack(Material.DAYLIGHT_DETECTOR));   // addAndPickItem
            inv.setHeldItemSlot(e.getTargetSlot());
        }
        return e;
    }

    ItemStack held() { return player.getInventory().getItemInMainHand(); }

    // ---- the cases ----

    @Test void logicBlockOnTheHotbarIsSelectedEvenWithAPlainDetectorThere() {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItem(1, new ItemStack(Material.DAYLIGHT_DETECTOR));
        player.getInventory().setItem(5, LogicItem.create(station));
        middleClick(false);
        assertEquals(5, player.getInventory().getHeldItemSlot(), "this is the case that failed in game");
        assertTrue(LogicItem.is(held()));
    }

    @Test void sameRulesFirstThenAny() {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(2, LogicItem.create(Program.EMPTY));
        player.getInventory().setItem(7, LogicItem.create(station));
        middleClick(false);
        assertEquals(station, LogicItem.program(held()), "the one with the block's rules");
        player.getInventory().setItem(7, null);
        middleClick(false);
        assertEquals(Program.EMPTY, LogicItem.program(held()), "else any Logic Block");
    }

    @Test void logicBlockInTheMainInventoryIsSwappedIntoTheHotbar() {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItem(0, new ItemStack(Material.STONE));
        player.getInventory().setItem(4, new ItemStack(Material.DAYLIGHT_DETECTOR));
        player.getInventory().setItem(20, LogicItem.create(station));
        middleClick(false);
        assertTrue(LogicItem.is(held()), "now in hand");
        assertTrue(player.getInventory().getHeldItemSlot() < 9);
        assertEquals(Material.DAYLIGHT_DETECTOR, player.getInventory().getItem(4).getType(), "the plain detector wasn't touched");
    }

    @Test void survivalWithOnlyPlainDetectorsPicksNothing() {
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItem(3, new ItemStack(Material.DAYLIGHT_DETECTOR));
        assertTrue(middleClick(false).isCancelled());
        assertEquals(0, player.getInventory().getHeldItemSlot(), "selection unchanged");
    }

    @Test void creativeGetsABlankLogicBlockOrWithCtrlACopyOfTheRules() {
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItem(0, new ItemStack(Material.STONE));
        middleClick(false);
        assertTrue(LogicItem.is(held()), "a Logic Block, not the plain detector the game would add");
        assertEquals(1, player.getInventory().getHeldItemSlot(), "first empty hotbar slot");
        assertEquals(Program.EMPTY, LogicItem.program(held()));
        player.getInventory().setItem(1, null);
        middleClick(true);
        assertEquals(station, LogicItem.program(held()), "Ctrl copies the block's rules");
    }

    @Test void itemsKeepRulesThatAreOnlyOnTheWhenTab() {
        assertEquals(station, LogicItem.program(LogicItem.create(station)), "a sorter station broken and placed again keeps its rule");
    }

    @Test void plainDetectorsAreLeftAlone() {
        Block plain = world.getBlockAt(5, 70, 5);
        plain.setType(Material.DAYLIGHT_DETECTOR);
        block = plain;
        player.setGameMode(GameMode.SURVIVAL);
        player.getInventory().setItem(3, new ItemStack(Material.DAYLIGHT_DETECTOR));
        player.getInventory().setItem(6, LogicItem.create(station));
        middleClick(false);
        assertEquals(3, player.getInventory().getHeldItemSlot(), "a real daylight detector picks a real one");
    }
}
