package dev.whizzle.logic;

import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Container;
import org.bukkit.block.Orientation;
import org.bukkit.block.ShulkerBox;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.Hopper;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ready-made machines for {@code /logic demo}, checked on a real server by the self-test (docs/BUILDS.md explains each).
 * Every machine fits in a box starting at (x, y, z) and reaching east (+x), up and south (+z): {@link #size} gives it.
 * They use only vanilla blocks; the Logic Blocks fill, empty and time them within the five rules.
 */
public final class Machines {
    private Machines() {}

    /** The machines, by command name. */
    public enum Kind {
        IRON_CRAFTER("iron-crafter"), ROCKET_CRAFTER("rocket-crafter"), SUPER_SMELTER("super-smelter"),
        SHULKER_LOADER("shulker-loader"), SHULKER_UNLOADER("shulker-unloader");
        public final String command;
        Kind(String command) { this.command = command; }
        public static Kind of(String command) {
            for (Kind k : values()) if (k.command.equals(command)) return k;
            return null;
        }
    }

    /** What was built: its Logic Blocks, where to put things in, where results come out. */
    public record Built(List<Block> logic, List<Block> inputs, Block output) {}

    public static final int MAX_FURNACES = 8;

    /** Width (x), height, depth (z) of a machine; {@code n} is the furnace count for the super smelter. */
    public static int[] size(Kind kind, int n) {
        return switch (kind) {
            case IRON_CRAFTER, ROCKET_CRAFTER -> new int[]{2, 2, 2};
            case SUPER_SMELTER -> new int[]{n + 2, 6, 2};
            case SHULKER_LOADER, SHULKER_UNLOADER -> new int[]{4, 5, 4};
        };
    }

    /** The first block in the way of the machine there, or null if its box is clear. */
    public static Block blocked(World w, int x, int y, int z, Kind kind, int n) {
        int[] s = size(kind, n);
        for (int dx = 0; dx < s[0]; dx++) for (int dy = 0; dy < s[1]; dy++) for (int dz = 0; dz < s[2]; dz++) {
            Block b = w.getBlockAt(x + dx, y + dy, z + dz);
            if (!b.getType().isAir() && !b.isReplaceable()) return b;
        }
        return null;
    }

    /** Builds a machine, programmed and owned, with its inputs stocked so it runs straight away. */
    public static Built build(LogicManager m, World w, int x, int y, int z, Kind kind, int n, UUID owner, String name) {
        Built b = switch (kind) {
            case IRON_CRAFTER -> crafter(m, w, x, y, z, ironCrafter(), 9, owner, name);
            case ROCKET_CRAFTER -> crafter(m, w, x, y, z, rocketCrafter(n), n + 1, owner, name);
            case SUPER_SMELTER -> superSmelter(m, w, x, y, z, n, owner, name);
            case SHULKER_LOADER -> shulker(m, w, x, y, z, true, owner, name);
            case SHULKER_UNLOADER -> shulker(m, w, x, y, z, false, owner, name);
        };
        stock(kind, n, b);
        return b;
    }

    // ---- crafters ----

    /**
     * The crafter's trigger, shared by both crafters: when the grid is full (a comparator would read 9) and timer T1
     * isn't running, output 15 for one check and start T1 for half a second. That's a rising edge (the crafter crafts
     * once per edge) and, while the grid stays full, another every half second, so a craft that didn't happen is tried
     * again.
     */
    static Program.Rule craftTrigger() {
        return new Program.Rule(List.of(Condition.fill(Side.EAST, Op.EQ, 9), Condition.timer("t1", false, true)),
            List.of(Action.set(15), Action.start("t1", 10)));
    }

    /** Iron ingots from the chest above into the crafter east of it, nine to a grid, then a pulse: iron blocks. */
    public static Program ironCrafter() {
        return new Program(List.of(craftTrigger()), Action.set(0),
            List.of(new Program.Rule(List.of(), Action.move("iron_ingot", Side.UP, Side.EAST))));
    }

    /**
     * Firework rockets of flight duration {@code flight} (1 to 3): the crafter has {@code flight} + 1 slots enabled, and
     * the Logic Block brings gunpowder until there are {@code flight}, and one paper.
     */
    public static Program rocketCrafter(int flight) {
        return new Program(List.of(craftTrigger()), Action.set(0), List.of(
            new Program.Rule(List.of(Condition.items(Side.EAST, "gunpowder", Op.LT, flight)), Action.move("gunpowder", Side.UP, Side.EAST)),
            new Program.Rule(List.of(Condition.items(Side.EAST, "paper", Op.EQ, 0)), Action.move("paper", Side.UP, Side.EAST))));
    }

    /** Logic Block (x, y, z) under the input chest, crafter east of it facing south into the output chest. */
    static Built crafter(LogicManager m, World w, int x, int y, int z, Program p, int enabledSlots, UUID owner, String name) {
        Block lb = logic(m, w.getBlockAt(x, y, z), p, owner, name);
        Block in = w.getBlockAt(x, y + 1, z);
        in.setType(Material.CHEST);
        Block crafter = w.getBlockAt(x + 1, y, z);
        crafter.setType(Material.CRAFTER, false);
        org.bukkit.block.data.type.Crafter data = (org.bukkit.block.data.type.Crafter) crafter.getBlockData();
        data.setOrientation(Orientation.SOUTH_UP);
        crafter.setBlockData(data, false);
        org.bukkit.block.Crafter state = (org.bukkit.block.Crafter) crafter.getState();
        for (int i = 0; i < 9; i++) state.setSlotDisabled(i, i >= enabledSlots);
        state.update(true, false);
        Block out = w.getBlockAt(x + 1, y, z + 1);
        out.setType(Material.CHEST);
        return new Built(List.of(lb), List.of(in), out);
    }

    // ---- super smelter ----

    /**
     * Above each furnace: hand it input while it holds fewer than 4 items in all (1 or 2 coal, the one cooking, one
     * waiting), and leave the rest in the line for the next furnaces, so the work spreads over all of them.
     */
    public static Program smelterInput() {
        return Program.EMPTY.withAlso(List.of(new Program.Rule(List.of(Condition.items(Side.DOWN, "*", Op.LT, 4)), Action.move("*", Side.UP, Side.DOWN))));
    }

    /** Below each furnace: take results out into the collection line, and keep 1 or 2 coal in it from the fuel line (south). */
    public static Program smelterService() {
        return Program.EMPTY.withAlso(List.of(
            new Program.Rule(List.of(), Action.move("*", Side.UP, Side.DOWN)),
            new Program.Rule(List.of(Condition.items(Side.UP, "coal", Op.LT, 2)), Action.move("coal", Side.SOUTH, Side.UP))));
    }

    /**
     * {@code n} furnaces in a row at y + 2, x + 1 to x + n, facing south. Layers, bottom up: collection line (hoppers
     * east into the output chest), service Logic Blocks with the fuel line south of them, furnaces, input Logic Blocks,
     * input line (hoppers east). Feeder chests: input at (x, y + 5, z), fuel at (x, y + 2, z + 1).
     */
    static Built superSmelter(LogicManager m, World w, int x, int y, int z, int n, UUID owner, String name) {
        if (n < 1 || n > MAX_FURNACES) throw new IllegalArgumentException("1 to " + MAX_FURNACES + " furnaces");
        List<Block> lbs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            int fx = x + 1 + i;
            hopper(w.getBlockAt(fx, y, z), BlockFace.EAST);
            lbs.add(logic(m, w.getBlockAt(fx, y + 1, z), smelterService(), owner, name));
            hopper(w.getBlockAt(fx, y + 1, z + 1), BlockFace.EAST);
            Block furnace = w.getBlockAt(fx, y + 2, z);
            furnace.setType(Material.FURNACE, false);
            facing(furnace, BlockFace.SOUTH);
            lbs.add(logic(m, w.getBlockAt(fx, y + 3, z), smelterInput(), owner, name));
            hopper(w.getBlockAt(fx, y + 4, z), BlockFace.EAST);
        }
        hopper(w.getBlockAt(x, y + 4, z), BlockFace.EAST);
        Block in = w.getBlockAt(x, y + 5, z);
        in.setType(Material.CHEST);
        hopper(w.getBlockAt(x, y + 1, z + 1), BlockFace.EAST);
        Block fuel = w.getBlockAt(x, y + 2, z + 1);
        fuel.setType(Material.CHEST);
        Block out = w.getBlockAt(x + 1 + n, y, z);
        out.setType(Material.CHEST);
        return new Built(lbs, List.of(in, fuel), out);
    }

    // ---- shulker loader and unloader ----

    /** Signals between the shulker machine's Logic Blocks: what the brain (LB-C) outputs. */
    public static final int BOX_WORKING = 0, BOX_MISSING = 7, BOX_DONE = 15;

    /**
     * The brain, beside the box spot (west of it): "done" (15) when the box is full (loader: a comparator would read 15)
     * or empty (unloader), "missing" (7) when there is no box (air, or the piston head), "working" (0) otherwise. The
     * loader's brain also fills the box from the chest east of it.
     */
    public static Program shulkerBrain(boolean loader) {
        Condition done = loader ? Condition.fill(Side.WEST, Op.EQ, 15) : Condition.items(Side.WEST, "*", Op.EQ, 0);
        Program p = new Program(List.of(
            new Program.Rule(List.of(done), Action.set(BOX_DONE)),
            new Program.Rule(List.of(Condition.fill(Side.WEST, Op.GE, 0)), Action.set(BOX_WORKING))), Action.set(BOX_MISSING));
        return loader ? p.withAlso(List.of(new Program.Rule(List.of(), Action.move("*", Side.EAST, Side.WEST)))) : p;
    }

    /**
     * Fires the block next to it (the piston or the dispenser) when the brain on side {@code brain} says {@code code}:
     * a 6-tick pulse, then again every second while it still says so (a dispenser that was empty, say).
     */
    public static Program shulkerFire(Side brain, int code) {
        return new Program(List.of(new Program.Rule(List.of(Condition.signal(brain, Op.EQ, code), Condition.timer("t1", false, true)),
            List.of(Action.pulse(15, 6), Action.start("t1", 20)))), Action.keep());
    }

    /**
     * LB-D: like {@link #shulkerFire} for "missing", but only while the dispenser (west of it) has a box in it, so an
     * empty dispenser isn't clicked every second; it fires as soon as the hopper above refills it (idea: the owner).
     */
    public static Program shulkerDispense() {
        return new Program(List.of(new Program.Rule(List.of(Condition.signal(Side.DOWN, Op.EQ, BOX_MISSING), Condition.timer("t1", false, true),
            Condition.fill(Side.WEST, Op.NE, 0)), List.of(Action.pulse(15, 6), Action.start("t1", 20)))), Action.keep());
    }

    /**
     * Box spot S at (x + 1, y + 1, z + 2). Above it a dispenser facing down places boxes (fed from the chest two above
     * it through a hopper). South of it a piston facing north breaks them and pushes the dropped box north, where it
     * falls (between glass on three sides) into the catch hopper at (x + 1, y, z + 1), which passes it west into the output chest. Under S: the unloader's
     * hopper (facing north into the catch hopper) empties the box; the loader has a solid block there. East of S the
     * brain LB-C, with LB-D above it (beside the dispenser) and LB-P south of it (beside the piston); the loader's
     * input chest is east of the brain.
     */
    static Built shulker(LogicManager m, World w, int x, int y, int z, boolean loader, UUID owner, String name) {
        Block out = w.getBlockAt(x, y, z + 1);
        out.setType(Material.CHEST);
        hopper(w.getBlockAt(x + 1, y, z + 1), BlockFace.WEST);
        if (loader) w.getBlockAt(x + 1, y, z + 2).setType(Material.SMOOTH_STONE);
        else hopper(w.getBlockAt(x + 1, y, z + 2), BlockFace.NORTH);
        Block dispenser = w.getBlockAt(x + 1, y + 2, z + 2);
        dispenser.setType(Material.DISPENSER, false);
        facing(dispenser, BlockFace.DOWN);
        hopper(w.getBlockAt(x + 1, y + 3, z + 2), BlockFace.DOWN);
        Block boxes = w.getBlockAt(x + 1, y + 4, z + 2);
        boxes.setType(Material.CHEST);
        Block piston = w.getBlockAt(x + 1, y + 1, z + 3);
        piston.setType(Material.PISTON, false);
        facing(piston, BlockFace.NORTH);
        // Glass west of the spot and around the cell the broken box is pushed into, so it can only end up in a hopper
        // (glass, so the output chest under one of them still opens).
        for (int[] g : new int[][]{{0, 2}, {0, 1}, {2, 1}, {1, 0}}) w.getBlockAt(x + g[0], y + 1, z + g[1]).setType(Material.GLASS);
        List<Block> lbs = new ArrayList<>();
        lbs.add(logic(m, w.getBlockAt(x + 2, y + 2, z + 2), shulkerDispense(), owner, name));
        lbs.add(logic(m, w.getBlockAt(x + 2, y + 1, z + 2), shulkerBrain(loader), owner, name));
        lbs.add(logic(m, w.getBlockAt(x + 2, y + 1, z + 3), shulkerFire(Side.NORTH, BOX_DONE), owner, name));
        List<Block> inputs = new ArrayList<>(List.of(boxes));
        if (loader) {
            Block in = w.getBlockAt(x + 3, y + 1, z + 2);
            in.setType(Material.CHEST);
            inputs.add(in);
        }
        return new Built(lbs, inputs, out);
    }

    // ---- stocking ----

    /** Unstackable items for the shulker demos, so a box fills (or empties) in about half a minute. */
    public static final List<Material> TOOLS = List.of(Material.WOODEN_SWORD, Material.STONE_PICKAXE, Material.IRON_SHOVEL, Material.GOLDEN_AXE,
        Material.WOODEN_HOE, Material.STONE_SWORD);

    /** What the demo starts with; the self-test checks the results of exactly this. */
    public static void stock(Kind kind, int n, Built b) {
        switch (kind) {
            case IRON_CRAFTER -> inv(b.inputs().get(0)).addItem(new ItemStack(Material.IRON_INGOT, 64), new ItemStack(Material.IRON_INGOT, 17));
            case ROCKET_CRAFTER -> inv(b.inputs().get(0)).addItem(new ItemStack(Material.PAPER, 6), new ItemStack(Material.GUNPOWDER, 6 * n));
            case SUPER_SMELTER -> {
                inv(b.inputs().get(0)).addItem(new ItemStack(Material.RAW_IRON, 2 * n));
                inv(b.inputs().get(1)).addItem(new ItemStack(Material.COAL, 2 * n));
            }
            case SHULKER_LOADER -> {
                inv(b.inputs().get(0)).addItem(new ItemStack(Material.SHULKER_BOX, 4));
                Inventory in = inv(b.inputs().get(1));
                for (int i = 0; i < 27; i++) in.setItem(i, new ItemStack(TOOLS.get(i % TOOLS.size())));   // one box's worth
            }
            case SHULKER_UNLOADER -> {
                Inventory boxes = inv(b.inputs().get(0));
                for (int i = 0; i < 3; i++) boxes.addItem(fullBox(i));
            }
        }
    }

    /** A shulker box item holding 6 tools and 2 stacks of 8 (for the unloader demo). */
    public static ItemStack fullBox(int i) {
        ItemStack box = new ItemStack(Material.SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) meta.getBlockState();
        for (int t = 0; t < 6; t++) state.getInventory().addItem(new ItemStack(TOOLS.get((t + i) % TOOLS.size())));
        state.getInventory().addItem(new ItemStack(Material.COBBLESTONE, 8), new ItemStack(Material.OAK_LOG, 8));
        meta.setBlockState(state);
        box.setItemMeta(meta);
        return box;
    }

    public static Inventory inv(Block b) { return ((Container) b.getState()).getInventory(); }

    // ---- helpers ----

    private static Block logic(LogicManager m, Block b, Program p, UUID owner, String name) {
        b.setType(LogicItem.BLOCK);
        m.create(b, p, owner, name);
        return b;
    }

    private static void facing(Block b, BlockFace f) {
        Directional d = (Directional) b.getBlockData();
        d.setFacing(f);
        b.setBlockData(d, false);
    }

    private static void hopper(Block b, BlockFace facing) {
        b.setType(Material.HOPPER);
        Hopper d = (Hopper) b.getBlockData();
        d.setFacing(facing);
        b.setBlockData(d);
    }
}
