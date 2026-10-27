package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ready-made builds, placed by {@code /logic demo} and checked on a real server by the self-test, so what players get
 * is exactly what was tested. See docs/SORTER.md for the sorter.
 */
public final class Demos {
    public static final int MAX_STATIONS = 8;
    public static final List<Material> DEFAULT_SORTED = List.of(Material.DIAMOND, Material.EMERALD, Material.DIAMOND_SWORD);
    private static final List<Material> FILLER = List.of(Material.STONE, Material.COBBLESTONE, Material.IRON_INGOT, Material.OAK_LOG);

    private Demos() {}

    /** What a sorter was built from: the feeder chest, each station's chest, and the overflow chest. */
    public record Sorter(Block feeder, List<Block> chests, Block overflow) {}

    // ---- the v2 sorter: one Logic Block per station, moving items ----

    /**
     * The blocks the v2 sorter occupies. The line runs east at y + 2 from a feeder hopper at x - 1 (under a feeder chest);
     * each station column is chest (y), Logic Block (y + 1), line hopper (y + 2); the overflow chest ends the line.
     */
    public static List<Block> moveSorterSpace(World w, int x, int y, int z, int stations) {
        List<Block> space = new ArrayList<>();
        for (int i = 0; i < stations; i++) for (int dy = 0; dy <= 2; dy++) space.add(w.getBlockAt(x + i, y + dy, z));
        space.add(w.getBlockAt(x - 1, y + 2, z));
        space.add(w.getBlockAt(x - 1, y + 3, z));
        space.add(w.getBlockAt(x + stations, y + 2, z));
        return space;
    }

    public static Block moveSorterBlocked(World w, int x, int y, int z, int stations) {
        for (Block b : moveSorterSpace(w, x, y, z, stations)) if (!b.getType().isAir() && !b.isReplaceable()) return b;
        return null;
    }

    /** Builds the v2 sorter: under each line hopper a Logic Block that moves its item down into the chest below it. */
    public static Sorter moveSorter(LogicManager manager, World w, int x, int y, int z, List<Material> sorted, UUID owner, String ownerName) {
        if (sorted.isEmpty() || sorted.size() > MAX_STATIONS) throw new IllegalArgumentException("1 to " + MAX_STATIONS + " stations");
        List<Block> chests = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Block chest = w.getBlockAt(x + i, y, z);
            chest.setType(Material.CHEST);
            chests.add(chest);
            Block lb = w.getBlockAt(x + i, y + 1, z);
            lb.setType(LogicItem.BLOCK);
            manager.create(lb, moveStation(sorted.get(i)), owner, ownerName);
            hopper(w.getBlockAt(x + i, y + 2, z), BlockFace.EAST);
        }
        hopper(w.getBlockAt(x - 1, y + 2, z), BlockFace.EAST);
        Block feeder = w.getBlockAt(x - 1, y + 3, z);
        feeder.setType(Material.CHEST);
        Block overflow = w.getBlockAt(x + sorted.size(), y + 2, z);
        overflow.setType(Material.CHEST);
        return new Sorter(feeder, chests, overflow);
    }

    /** The whole v2 station program: also, always, move the item from up (the line hopper) to down (the chest). */
    public static Program moveStation(Material item) {
        return Program.EMPTY.withAlso(List.of(new Program.Rule(List.of(), Action.move(id(item), Side.UP, Side.DOWN))));
    }

    // ---- the v1 sorter: pure redstone, two Logic Blocks per station ----

    /**
     * The blocks a sorter with {@code stations} stations occupies. The line runs east from (x, y + 3, z): a feeder
     * hopper at x - 1 under a feeder chest, one station per block from x, the overflow chest at the end. Station
     * columns are chest (y), drain hopper, pull hopper, line hopper, glass cover (y + 4); the two Logic Blocks sit
     * north of the line and pull hoppers (z - 1).
     */
    public static List<Block> sorterSpace(World w, int x, int y, int z, int stations) {
        List<Block> space = new ArrayList<>();
        for (int i = 0; i < stations; i++) {
            for (int dy = 0; dy <= 4; dy++) space.add(w.getBlockAt(x + i, y + dy, z));
            space.add(w.getBlockAt(x + i, y + 3, z - 1));
            space.add(w.getBlockAt(x + i, y + 2, z - 1));
        }
        space.add(w.getBlockAt(x - 1, y + 3, z));
        space.add(w.getBlockAt(x - 1, y + 4, z));
        space.add(w.getBlockAt(x + stations, y + 3, z));
        return space;
    }

    /** The first block in the way of a sorter there, or null if the space is free. */
    public static Block sorterBlocked(World w, int x, int y, int z, int stations) {
        for (Block b : sorterSpace(w, x, y, z, stations)) if (!b.getType().isAir() && !b.isReplaceable()) return b;
        return null;
    }

    /** Builds the sorter: one station per item in {@code sorted}, in order along the line. */
    public static Sorter sorter(LogicManager manager, World w, int x, int y, int z, List<Material> sorted, UUID owner, String ownerName) {
        if (sorted.isEmpty() || sorted.size() > MAX_STATIONS) throw new IllegalArgumentException("1 to " + MAX_STATIONS + " stations");
        List<Block> chests = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            int sx = x + i;
            Block chest = w.getBlockAt(sx, y, z);
            chest.setType(Material.CHEST);
            chests.add(chest);
            hopper(w.getBlockAt(sx, y + 1, z), BlockFace.DOWN);   // D: drains X, even while X is locked
            hopper(w.getBlockAt(sx, y + 2, z), BlockFace.DOWN);   // X: pulls this station's item out of S
            hopper(w.getBlockAt(sx, y + 3, z), BlockFace.EAST);   // S: the line
            w.getBlockAt(sx, y + 4, z).setType(Material.GLASS);   // cover: nothing can drop into S from above
            Block lb1 = w.getBlockAt(sx, y + 3, z - 1), lb2 = w.getBlockAt(sx, y + 2, z - 1);
            lb1.setType(LogicItem.BLOCK);
            manager.create(lb1, stationReader(sorted.get(i)), owner, ownerName);
            lb2.setType(LogicItem.BLOCK);
            manager.create(lb2, stationGate(sorted.get(i)), owner, ownerName);
        }
        hopper(w.getBlockAt(x - 1, y + 3, z), BlockFace.EAST);
        Block feeder = w.getBlockAt(x - 1, y + 4, z);
        feeder.setType(Material.CHEST);
        Block overflow = w.getBlockAt(x + sorted.size(), y + 3, z);
        overflow.setType(Material.CHEST);
        return new Sorter(feeder, chests, overflow);
    }

    /** LB1: the line hopper (south) holds this item and nothing else, and LB2 (below) isn't saying "full" -> lock it. */
    public static Program stationReader(Material item) {
        return new Program(List.of(new Program.Rule(List.of(
            Condition.items(Side.SOUTH, id(item), Op.GE, 1),
            Condition.items(Side.SOUTH, "*", Op.LE, 1),
            Condition.signal(Side.DOWN, Op.NE, 7)), Action.set(15))), Action.set(0));
    }

    /** LB2: the pull hopper (south) is full -> 7 ("full", for LB1); LB1 (above) is on -> unlock it; otherwise lock it. */
    public static Program stationGate(Material item) {
        return new Program(List.of(
            new Program.Rule(List.of(Condition.items(Side.SOUTH, id(item), Op.GE, 5 * item.getMaxStackSize())), Action.set(7)),
            new Program.Rule(List.of(Condition.signal(Side.UP, Op.GT, 0)), Action.set(0))), Action.set(15));
    }

    /** A mix to watch it work: some of each sorted item between filler items. */
    public static void fillSample(Inventory inv, List<Material> sorted) {
        int slot = 0;
        for (int round = 0; round < 3 && slot < inv.getSize(); round++)
            for (int i = 0; i < sorted.size() && slot < inv.getSize() - 1; i++) {
                Material m = sorted.get(i);
                inv.setItem(slot++, new ItemStack(m, m.getMaxStackSize() == 1 ? 1 : 2 + round));
                Material f = FILLER.get((round + i) % FILLER.size());
                inv.setItem(slot++, new ItemStack(f, 1 + (round + i) % 3));
            }
    }

    public static Inventory inventory(Block b) { return ((Container) b.getState()).getInventory(); }

    private static String id(Material m) { return m.getKey().getKey(); }

    private static void hopper(Block b, BlockFace facing) {
        b.setType(Material.HOPPER);
        Hopper d = (Hopper) b.getBlockData();
        d.setFacing(facing);
        b.setBlockData(d);
    }
}
