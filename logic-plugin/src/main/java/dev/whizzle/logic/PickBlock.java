package dev.whizzle.logic;

import dev.whizzle.logic.model.Program;
import io.papermc.paper.event.player.PlayerPickBlockEvent;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/**
 * Middle-click ("pick block") on a Logic Block picks a Logic Block, not a plain daylight detector (which is what the
 * game would pick, since that's what the block is). Prefers a Logic Block item with the same rules; in creative, hands
 * out a blank one, or with Ctrl a copy carrying that block's rules; never a plain daylight detector.
 */
final class PickBlock implements Listener {
    private final LogicManager manager;

    PickBlock(LogicManager manager) { this.manager = manager; }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPick(PlayerPickBlockEvent e) {
        LogicNode node = manager.at(e.getBlock()).orElse(null);
        if (node == null) return;
        Player p = e.getPlayer();
        PlayerInventory inv = p.getInventory();
        int slot = bestSlot(inv, node.program());
        if (slot >= 0) {
            e.setSourceSlot(slot);
            // Paper selects the TARGET slot when the source is already on the hotbar, and otherwise swaps the source
            // into the target. The target it chose was for the plain daylight detector, so choose it again.
            e.setTargetSlot(slot < 9 ? slot : hotbarSlotFor(inv));
            return;
        }
        e.setCancelled(true);   // no Logic Block in the inventory: don't pick a plain daylight detector
        if (p.getGameMode() != GameMode.CREATIVE) return;
        ItemStack item = LogicItem.create(e.isIncludeData() ? node.program() : Program.EMPTY);
        int target = hotbarSlotFor(inv);
        inv.setItem(target, item);
        inv.setHeldItemSlot(target);
    }

    /** A Logic Block item in the inventory: one carrying {@code program} if there is one, else any; -1 if none. */
    static int bestSlot(PlayerInventory inv, Program program) {
        int any = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack item = inv.getItem(i);
            if (!LogicItem.is(item)) continue;
            if (LogicItem.program(item).equals(program)) return i;
            if (any < 0) any = i;
        }
        return any;
    }

    /** Like vanilla: the selected slot if it's empty, else the first empty hotbar slot, else the selected slot. */
    static int hotbarSlotFor(PlayerInventory inv) {
        int selected = inv.getHeldItemSlot();
        if (empty(inv.getItem(selected))) return selected;
        for (int i = 0; i < 9; i++) if (empty(inv.getItem(i))) return i;
        return selected;
    }

    private static boolean empty(ItemStack item) { return item == null || item.getType().isAir(); }
}
