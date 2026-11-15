package dev.whizzle.logic;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Logic Block items made before plugin 0.5.0 have no model string, so the mod can't draw them and they don't stack
 * with new ones. They're upgraded where players meet them: their own inventory and ender chest when they join, and
 * any container they open. Placed blocks need nothing: breaking one drops a new item.
 */
final class ItemUpgrade implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent e) {
        LogicItem.upgrade(e.getPlayer().getInventory());
        LogicItem.upgrade(e.getPlayer().getEnderChest());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent e) { LogicItem.upgrade(e.getInventory()); }
}
