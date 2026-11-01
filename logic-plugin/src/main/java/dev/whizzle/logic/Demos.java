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

    // ---- counters and timers ----

    /** What the lock demo was built from: the Logic Block, the buttons in the order to press them, and the door. */
    public record Lock(Block logic, List<Block> buttons, Block door) {}

    /**
     * The combination lock's program: buttons on the west, north and east sides, pressed in that order, open the door
     * on the south side for 3 seconds. Counter A remembers how far along the combination you are; a press out of
     * order puts it back to 0.
     */
    public static Program lockProgram() {
        Program.Rule open = new Program.Rule(List.of(Condition.counter("a", Op.EQ, 3)), List.of(Action.pulse(15, 60), Action.setCounter("a", 0)));
        return new Program(List.of(open), Action.keep(), List.of(
            new Program.Rule(List.of(Condition.change(Side.WEST, true)), Action.setCounter("a", 1)),
            new Program.Rule(List.of(Condition.change(Side.NORTH, true), Condition.counter("a", Op.EQ, 1)), Action.setCounter("a", 2)),
            new Program.Rule(List.of(Condition.change(Side.EAST, true), Condition.counter("a", Op.EQ, 2)), Action.setCounter("a", 3)),
            new Program.Rule(List.of(Condition.change(Side.NORTH, true), Condition.counter("a", Op.NE, 1)), Action.setCounter("a", 0)),
            new Program.Rule(List.of(Condition.change(Side.EAST, true), Condition.counter("a", Op.NE, 2)), Action.setCounter("a", 0))));
    }

    /** Stays on while the west signal is on and for {@code ticks} after it goes off (a timer started when it goes off). */
    public static Program delayedOff(int ticks) {
        return new Program(List.of(
            new Program.Rule(List.of(Condition.signal(Side.WEST, Op.GT, 0)), Action.set(15)),
            new Program.Rule(List.of(Condition.timer("t1", true, false)), Action.set(0))), Action.keep(),
            List.of(new Program.Rule(List.of(Condition.signal(Side.WEST, Op.EQ, 0)), Action.start("t1", ticks))));
    }

    /** A clock: a {@code pulse}-tick pulse every {@code period} ticks, from a timer that restarts itself. */
    public static Program clock(int period, int pulse) {
        return new Program(List.of(new Program.Rule(List.of(Condition.timer("t1", true, false)), Action.pulse(15, pulse))), Action.keep(),
            List.of(new Program.Rule(List.of(Condition.timer("t1", false, true)), Action.start("t1", period))));
    }

    /**
     * The lock: the Logic Block at (x, y, z), a stone block on its west, north and east sides with a button on the far
     * face and a numbered sign on top (1, 2, 3: the order), and an iron door on its south side.
     */
    public static List<Block> lockSpace(World w, int x, int y, int z) {
        List<Block> space = new ArrayList<>();
        space.add(w.getBlockAt(x, y, z));
        for (BlockFace f : List.of(BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST))
            for (int d = 1; d <= 2; d++) {
                space.add(w.getBlockAt(x + f.getModX() * d, y, z + f.getModZ() * d));
                if (d == 1) space.add(w.getBlockAt(x + f.getModX(), y + 1, z + f.getModZ()));
            }
        space.add(w.getBlockAt(x, y, z + 1));
        space.add(w.getBlockAt(x, y + 1, z + 1));
        return space;
    }

    public static Block lockBlocked(World w, int x, int y, int z) {
        for (Block b : lockSpace(w, x, y, z)) if (!b.getType().isAir() && !b.isReplaceable()) return b;
        return null;
    }

    public static Lock lock(LogicManager manager, World w, int x, int y, int z, UUID owner, String ownerName) {
        Block logic = w.getBlockAt(x, y, z);
        logic.setType(LogicItem.BLOCK);
        manager.create(logic, lockProgram(), owner, ownerName);
        List<Block> buttons = new ArrayList<>();
        int n = 1;
        for (BlockFace f : List.of(BlockFace.WEST, BlockFace.NORTH, BlockFace.EAST)) {
            Block stone = w.getBlockAt(x + f.getModX(), y, z + f.getModZ());
            stone.setType(Material.STONE_BRICKS);
            Block button = stone.getRelative(f);
            button.setType(Material.STONE_BUTTON, false);
            org.bukkit.block.data.type.Switch sw = (org.bukkit.block.data.type.Switch) button.getBlockData();
            sw.setAttachedFace(org.bukkit.block.data.FaceAttachable.AttachedFace.WALL);
            sw.setFacing(f);
            button.setBlockData(sw, false);
            buttons.add(button);
            Block sign = stone.getRelative(BlockFace.UP);
            sign.setType(Material.OAK_SIGN, false);
            org.bukkit.block.data.type.Sign sd = (org.bukkit.block.data.type.Sign) sign.getBlockData();
            sd.setRotation(f);
            sign.setBlockData(sd, false);
            org.bukkit.block.Sign state = (org.bukkit.block.Sign) sign.getState();
            state.getSide(org.bukkit.block.sign.Side.FRONT).line(1, net.kyori.adventure.text.Component.text(String.valueOf(n++)));
            state.update(true, false);
        }
        Block below = w.getBlockAt(x, y - 1, z + 1);
        if (below.getType().isAir() || below.isReplaceable()) below.setType(Material.STONE_BRICKS);
        Block door = w.getBlockAt(x, y, z + 1);
        for (int half = 0; half < 2; half++) {
            Block b = door.getRelative(0, half, 0);
            b.setType(Material.IRON_DOOR, false);
            org.bukkit.block.data.type.Door d = (org.bukkit.block.data.type.Door) b.getBlockData();
            d.setHalf(half == 0 ? org.bukkit.block.data.Bisected.Half.BOTTOM : org.bukkit.block.data.Bisected.Half.TOP);
            d.setFacing(BlockFace.NORTH);
            b.setBlockData(d, false);
        }
        return new Lock(logic, buttons, door);
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
