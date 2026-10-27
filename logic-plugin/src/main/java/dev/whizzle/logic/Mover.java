package dev.whizzle.logic;

import dev.whizzle.logic.model.Action;
import dev.whizzle.logic.model.Side;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.*;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/**
 * The "move items" action, held to the five rules for anything beyond redstone (docs/DESIGN.md):
 * <ol>
 *   <li>Only what a hopper could do: containers a hopper works with, through the faces a hopper would use (a furnace
 *       takes smelting input through its top, fuel through its sides and bottom, and gives results out of the bottom).</li>
 *   <li>Only the containers touching the Logic Block; never loads a chunk.</li>
 *   <li>One item per {@link #COOLDOWN} ticks per Logic Block, a hopper's speed.</li>
 *   <li>Moves items, never creates or destroys them.</li>
 *   <li>Fires {@link InventoryMoveItemEvent} like a hopper, so protection plugins can stop it.</li>
 * </ol>
 */
final class Mover {
    /** A hopper moves one item every 8 ticks. */
    static final int COOLDOWN = 8;

    private Mover() {}

    /** Moves one item for {@code a} (a MOVE action) next to {@code block}; true if an item moved. */
    static boolean move(Block block, Action a) {
        Box from = box(block, a.from()), to = box(block, a.to());
        if (from == null || to == null) return false;
        for (int slot : from.extractSlots()) {
            ItemStack stack = from.inv.getItem(slot);
            if (stack == null || stack.getType().isAir()) continue;
            if (!a.id().equals("*") && !a.id().equals(WorldInputs.id(stack.getType()))) continue;
            if (!from.canExtract(slot, stack)) continue;
            ItemStack one = stack.asOne();
            int target = to.slotFor(one);
            if (target < 0) continue;
            InventoryMoveItemEvent event = new InventoryMoveItemEvent(from.inv, one.clone(), to.inv, false);
            Bukkit.getPluginManager().callEvent(event);
            if (event.isCancelled()) return false;
            ItemStack there = to.inv.getItem(target);
            if (there == null || there.getType().isAir()) to.inv.setItem(target, one);
            else there.setAmount(there.getAmount() + 1);
            stack.setAmount(stack.getAmount() - 1);
            from.inv.setItem(slot, stack.getAmount() <= 0 ? null : stack);
            return true;
        }
        return false;
    }

    /** A container next to the Logic Block, and the face of it that touches the block. */
    private record Box(Inventory inv, BlockState state, Side face) {
        boolean furnace() { return state instanceof Furnace; }

        /** Slots a hopper could take from through this face, in the order a hopper tries them. */
        int[] extractSlots() {
            if (!furnace()) return range(inv.getSize());
            return switch (face) { case DOWN -> new int[]{2, 1}; case UP -> new int[]{0}; default -> new int[]{1}; };
        }

        boolean canExtract(int slot, ItemStack stack) {
            // A furnace only gives up fuel-slot items through its bottom if they're (water) buckets, as for hoppers.
            return !(furnace() && face == Side.DOWN && slot == 1 && stack.getType() != Material.BUCKET && stack.getType() != Material.WATER_BUCKET);
        }

        /** The slot one more of {@code one} goes into through this face, or -1 if it doesn't fit. */
        int slotFor(ItemStack one) {
            if (state instanceof ShulkerBox && Tag.SHULKER_BOXES.isTagged(one.getType())) return -1;
            int[] slots = !furnace() ? range(inv.getSize())
                : face == Side.UP ? new int[]{0} : one.getType().isFuel() ? new int[]{1} : new int[0];
            int max = Math.min(one.getMaxStackSize(), inv.getMaxStackSize());
            for (int s : slots) {
                ItemStack there = inv.getItem(s);
                if (there == null || there.getType().isAir()) return s;
                if (there.isSimilar(one) && there.getAmount() < max) return s;
            }
            return -1;
        }
    }

    private static int[] range(int n) { int[] a = new int[n]; for (int i = 0; i < n; i++) a[i] = i; return a; }

    /** The container on {@code side} of the Logic Block if a hopper could use it, else null. */
    private static Box box(Block block, Side side) {
        if (side == Side.ANY) return null;
        BlockFace f = WorldInputs.face(side);
        int x = block.getX() + f.getModX(), z = block.getZ() + f.getModZ();
        if (!block.getWorld().isChunkLoaded(x >> 4, z >> 4)) return null;
        BlockState state = block.getRelative(f).getState(false);
        boolean usable = state instanceof Chest || state instanceof Barrel || state instanceof Hopper || state instanceof Dropper
            || state instanceof Dispenser || state instanceof ShulkerBox || state instanceof Furnace;
        if (!usable || !(state instanceof Container c)) return null;
        return new Box(c.getInventory(), state, opposite(side));
    }

    private static Side opposite(Side s) {
        return switch (s) {
            case NORTH -> Side.SOUTH; case SOUTH -> Side.NORTH; case EAST -> Side.WEST; case WEST -> Side.EAST;
            case UP -> Side.DOWN; case DOWN -> Side.UP; case ANY -> Side.ANY;
        };
    }
}
