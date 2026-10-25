package dev.whizzle.logic.selftest;

import dev.whizzle.logic.LogicBlocksPlugin;
import dev.whizzle.logic.LogicManager;
import dev.whizzle.logic.LogicItem;
import dev.whizzle.logic.LogicNode;
import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Chest;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.type.RedstoneWire;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;

/**
 * Development-only: builds small circuits around Logic Blocks on a real Paper server and checks what the redstone
 * actually does. Writes report.txt; with -Dlogic.selftest.exit=true it stops the server when done.
 */
public final class SelfTestPlugin extends JavaPlugin {
    record Step(String name, Runnable action, int ticks, Supplier<String> check) {}

    private final List<Step> steps = new ArrayList<>();
    private final List<String> report = new ArrayList<>();
    private World w;
    private LogicManager manager;
    private static final int Y = -60;
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-00000000c0de");
    private int failures;

    @Override public void onEnable() { getServer().getScheduler().runTaskLater(this, this::start, 40); }

    Block at(int x, int z) { return w.getBlockAt(x, Y, z); }
    int wire(int x, int z) { BlockData d = at(x, z).getBlockData(); return d instanceof RedstoneWire r ? r.getPower() : -1; }
    int power(int x, int z) { return at(x, z).getBlockData() instanceof AnaloguePowerable p ? p.getPower() : -1; }

    LogicNode node(int x, int z, Program p) {
        Block b = at(x, z);
        b.setType(LogicItem.BLOCK);
        return manager.create(b, p, OWNER, "SelfTest");
    }

    static Program.Rule rule(Action a, Condition... cs) { return new Program.Rule(List.of(cs), a); }

    void repeaterInto(int x, int z, BlockFace inputSide) {
        Block r = at(x, z);
        r.setType(Material.REPEATER);
        Directional d = (Directional) r.getBlockData();
        d.setFacing(inputSide);                 // a repeater's facing points at its input
        r.setBlockData(d);
    }

    static String expect(String what, int got, int want) { return got == want ? null : what + " = " + got + ", expected " + want; }

    void start() {
        w = getServer().getWorlds().getFirst();
        manager = ((LogicBlocksPlugin) getServer().getPluginManager().getPlugin("LogicBlocks")).manager();
        for (int cx = -2; cx <= 8; cx++) for (int cz = -2; cz <= 2; cz++) w.setChunkForceLoaded(cx, cz, true);
        w.setChunkForceLoaded(404 >> 4, 0, true);
        for (int x = 400; x <= 410; x++) for (int z = 0; z <= 3; z++) {
            w.getBlockAt(x, Y - 1, z).setType(Material.STONE);
            for (int y = Y; y < Y + 4; y++) w.getBlockAt(x, y, z).setType(Material.AIR);
        }
        for (int x = -20; x <= 130; x++) for (int z = -20; z <= 20; z++) {
            w.getBlockAt(x, Y - 1, z).setType(Material.STONE);
            for (int y = Y; y < Y + 4; y++) w.getBlockAt(x, y, z).setType(Material.AIR);
        }

        // 1. Block rule: red wool to the north turns the output on, and only the right block does.
        int a = 0;
        steps.add(new Step("block rule: nothing north", () -> {
            node(a, 0, new Program(List.of(rule(Action.set(15), Condition.block(Side.NORTH, true, "red_wool")),
                rule(Action.set(6), Condition.block(Side.NORTH, true, "blue_wool"))), Action.set(0)));
            at(a + 1, 0).setType(Material.REDSTONE_WIRE);
            at(a - 1, 0).setType(Material.REDSTONE_LAMP);
        }, 6, () -> expect("wire", wire(a + 1, 0), 0)));
        steps.add(new Step("block rule: red wool north -> 15, lamp lit", () -> at(a, -1).setType(Material.RED_WOOL), 6,
            () -> Optional.ofNullable(expect("wire", wire(a + 1, 0), 15)).orElse(((org.bukkit.block.data.Lightable) at(a - 1, 0).getBlockData()).isLit() ? null : "lamp not lit")));
        steps.add(new Step("block rule: blue wool north -> 6", () -> at(a, -1).setType(Material.BLUE_WOOL), 6, () -> expect("wire", wire(a + 1, 0), 6)));
        steps.add(new Step("block rule: wool removed -> 0", () -> at(a, -1).setType(Material.AIR), 6, () -> expect("wire", wire(a + 1, 0), 0)));

        // 2. Incoming signal through a repeater, compared as a number.
        int b = 10;
        steps.add(new Step("signal rule: no input", () -> {
            node(b, 0, new Program(List.of(rule(Action.set(9), Condition.signal(Side.WEST, Op.GE, 1))), Action.set(0)));
            repeaterInto(b - 1, 0, BlockFace.WEST);
            at(b + 1, 0).setType(Material.REDSTONE_WIRE);
        }, 6, () -> expect("wire", wire(b + 1, 0), 0)));
        steps.add(new Step("signal rule: power on through a repeater -> 9", () -> at(b - 2, 0).setType(Material.REDSTONE_BLOCK), 8,
            () -> expect("wire", wire(b + 1, 0), 9)));
        steps.add(new Step("signal rule: power off -> 0", () -> at(b - 2, 0).setType(Material.AIR), 8, () -> expect("wire", wire(b + 1, 0), 0)));

        // 3. Items in the container below.
        int c = 20;
        steps.add(new Step("item rule: empty chest below -> 0", () -> {
            node(c, 0, new Program(List.of(rule(Action.set(12), Condition.items(Side.DOWN, "coal", Op.GE, 5))), Action.set(0)));
            w.getBlockAt(c, Y - 1, 0).setType(Material.CHEST);
            at(c + 1, 0).setType(Material.REDSTONE_WIRE);
        }, 6, () -> expect("wire", wire(c + 1, 0), 0)));
        steps.add(new Step("item rule: 5 coal -> 12", () -> ((Chest) w.getBlockAt(c, Y - 1, 0).getState()).getInventory().addItem(new ItemStack(Material.COAL, 5)), 6,
            () -> expect("wire", wire(c + 1, 0), 12)));
        steps.add(new Step("item rule: 4 coal -> 0", () -> ((Chest) w.getBlockAt(c, Y - 1, 0).getState()).getInventory().removeItem(new ItemStack(Material.COAL, 1)), 6,
            () -> expect("wire", wire(c + 1, 0), 0)));

        // 4. Toggle: each press flips the output and it stays (otherwise keep).
        int d = 30;
        steps.add(new Step("toggle: placed, off", () -> {
            node(d, 0, new Program(List.of(rule(Action.toggle(15), Condition.signal(Side.WEST, Op.GT, 0))), Action.keep()));
            repeaterInto(d - 1, 0, BlockFace.WEST);
            at(d + 1, 0).setType(Material.REDSTONE_WIRE);
        }, 6, () -> expect("wire", wire(d + 1, 0), 0)));
        for (int press = 1; press <= 3; press++) {
            int want = press % 2 == 1 ? 15 : 0;
            steps.add(new Step("toggle: press " + press, () -> at(d - 2, 0).setType(Material.REDSTONE_BLOCK), 8, () -> null));
            steps.add(new Step("toggle: release " + press + " -> " + want, () -> at(d - 2, 0).setType(Material.AIR), 8, () -> expect("wire", wire(d + 1, 0), want)));
        }

        // 5. Pulse: input held, output on for 10 ticks then off.
        int e = 40;
        steps.add(new Step("pulse: placed", () -> {
            node(e, 0, new Program(List.of(rule(Action.pulse(15, 10), Condition.signal(Side.WEST, Op.GT, 0))), Action.keep()));
            repeaterInto(e - 1, 0, BlockFace.WEST);
            at(e + 1, 0).setType(Material.REDSTONE_WIRE);
        }, 6, () -> expect("wire", wire(e + 1, 0), 0)));
        steps.add(new Step("pulse: on soon after input", () -> at(e - 2, 0).setType(Material.REDSTONE_BLOCK), 7, () -> expect("wire", wire(e + 1, 0), 15)));
        steps.add(new Step("pulse: off after 10 ticks while input still held", () -> { }, 12, () -> expect("wire", wire(e + 1, 0), 0)));

        // 6. A block wired back into itself through dust becomes a clock, limited to one change per 2 ticks.
        int f = 50;
        int[] changes = {0, -1};
        steps.add(new Step("self-wired clock: rate limited", () -> {
            node(f, 0, new Program(List.of(rule(Action.set(0), Condition.signal(Side.ANY, Op.GT, 0))), Action.set(15)));
            at(f + 1, 0).setType(Material.REDSTONE_WIRE);
            at(f + 1, 1).setType(Material.REDSTONE_WIRE);
            at(f, 1).setType(Material.REDSTONE_WIRE);
            for (int i = 0; i < 60; i++) getServer().getScheduler().runTaskLater(this, () -> {
                int p = power(f, 0);
                if (p != changes[1]) { changes[0]++; changes[1] = p; }
            }, i + 1);
        }, 64, () -> {
            report.add("INFO self-wired clock: " + changes[0] + " output changes in 60 ticks");
            return changes[0] >= 4 && changes[0] <= 31 ? null : "changes = " + changes[0] + ", expected 4..31 (clock, but rate-limited)";
        }));

        // 7. Dust touching the block carries the block's own output back in (documented: use a repeater for inputs).
        int g = 60;
        steps.add(new Step("dust input feedback (documented behaviour)", () -> {
            node(g, 0, new Program(List.of(rule(Action.set(15), Condition.signal(Side.WEST, Op.GE, 1))), Action.set(0)));
            for (int x = g - 3; x < g; x++) at(x, 0).setType(Material.REDSTONE_WIRE);
            at(g - 4, 0).setType(Material.REDSTONE_BLOCK);
        }, 8, () -> null));
        steps.add(new Step("dust input feedback: source removed", () -> at(g - 4, 0).setType(Material.AIR), 10, () -> {
            report.add("INFO dust straight into a Logic Block: after the source is removed the output is " + power(g, 0)
                + (power(g, 0) == 15 ? " (latched by its own signal, as documented)" : " (released)"));
            return null;
        }));

        // 8. Daylight and night don't change the output (it's a daylight detector underneath).
        int h = 70;
        steps.add(new Step("daylight ignored: noon", () -> {
            node(h, 0, new Program(List.of(), Action.set(3)));
            at(h + 1, 0).setType(Material.REDSTONE_WIRE);
            w.setTime(6000);
        }, 45, () -> expect("wire", wire(h + 1, 0), 3)));
        steps.add(new Step("daylight ignored: midnight", () -> w.setTime(18000), 45, () -> expect("wire", wire(h + 1, 0), 3)));

        // 8b. Power coming in does not pass through the block to its other sides, and sides are read separately.
        int m = 85;
        steps.add(new Step("sides: placed", () -> {
            node(m, 0, new Program(List.of(rule(Action.set(5), Condition.signal(Side.NORTH, Op.GE, 1))), Action.set(0)));
            repeaterInto(m - 1, 0, BlockFace.WEST);
            Block r = at(m, -1); r.setType(Material.REPEATER);
            Directional rd = (Directional) r.getBlockData(); rd.setFacing(BlockFace.NORTH); r.setBlockData(rd);
            at(m + 1, 0).setType(Material.REDSTONE_WIRE);
        }, 6, () -> expect("wire", wire(m + 1, 0), 0)));
        steps.add(new Step("sides: west input only -> rule (north) false, nothing leaks through", () -> at(m - 2, 0).setType(Material.REDSTONE_BLOCK), 8,
            () -> expect("wire", wire(m + 1, 0), 0)));
        steps.add(new Step("sides: north input -> 5", () -> at(m, -2).setType(Material.REDSTONE_BLOCK), 8, () -> expect("wire", wire(m + 1, 0), 5)));
        steps.add(new Step("sides: exact per-side reading available", () -> { }, 1,
            () -> { LogicNode n = manager.at(at(m, 0)).orElse(null); return n == null ? "missing" : n.state().matched == 0 ? null : "rule not matched"; }));

        // 9. Survives its chunk unloading and loading again (an isolated chunk, so neighbours don't keep it loaded).
        int k = 404;
        steps.add(new Step("persistence: set up", () -> {
            LogicNode n = node(k, 0, new Program(List.of(rule(Action.toggle(11), Condition.signal(Side.WEST, Op.GT, 0))), Action.keep()));
            repeaterInto(k - 1, 0, BlockFace.WEST);
            at(k + 1, 0).setType(Material.REDSTONE_WIRE);
            at(k - 2, 0).setType(Material.REDSTONE_BLOCK);
        }, 8, () -> null));
        steps.add(new Step("persistence: latched on", () -> at(k - 2, 0).setType(Material.AIR), 8, () -> expect("wire", wire(k + 1, 0), 11)));
        steps.add(new Step("persistence: release chunk", () -> w.setChunkForceLoaded(k >> 4, 0, false), 2, () -> null));
        steps.add(new Step("persistence: chunk unloaded", () -> {
            if (!w.unloadChunk(k >> 4, 0, true)) report.add("INFO chunk refused to unload");
        }, 4, () -> w.isChunkLoaded(k >> 4, 0) ? "chunk still loaded" : manager.nodes().stream().anyMatch(n -> n.block().getX() == k) ? "still registered after unload" : null));
        steps.add(new Step("persistence: chunk loaded again keeps program and latch", () -> w.setChunkForceLoaded(k >> 4, 0, true), 8, () -> {
            LogicNode n = manager.at(at(k, 0)).orElse(null);
            if (n == null) return "not registered after reload";
            if (!n.state().toggled) return "latch lost";
            return expect("wire", wire(k + 1, 0), 11);
        }));

        // 10. Item sorter (docs/SORTER.md): stations of two Logic Blocks around a line of hoppers.
        sorter();

        // 11. Load: many busy blocks, timed.
        steps.add(new Step("load: 400 blocks x 8 rules x 3 conditions", this::buildLoad, 4, this::measureLoad));

        run(0);
    }

    // ---- item sorter ----

    static final int SY = Y;   // the sorter's lowest layer (chests)
    static final List<Material> MIX = List.of(Material.DIAMOND, Material.STONE, Material.EMERALD, Material.IRON_INGOT,
        Material.DIAMOND_SWORD, Material.COBBLESTONE, Material.DIAMOND, Material.EMERALD, Material.DIAMOND_SWORD);

    Block sb(int x, int y, int z) { return w.getBlockAt(x, SY + y, z); }

    void hopper(Block b, BlockFace facing) {
        b.setType(Material.HOPPER);
        org.bukkit.block.data.type.Hopper d = (org.bukkit.block.data.type.Hopper) b.getBlockData();
        d.setFacing(facing);
        b.setBlockData(d);
    }

    static org.bukkit.inventory.Inventory inv(Block b) { return ((org.bukkit.block.Container) b.getState()).getInventory(); }

    static int count(Block b, Material m) {
        int n = 0;
        for (ItemStack it : inv(b).getContents()) if (it != null && (m == null || it.getType() == m)) n += it.getAmount();
        return n;
    }

    static Map<Material, Integer> contents(Block b) {
        Map<Material, Integer> m = new TreeMap<>();
        for (ItemStack it : inv(b).getContents()) if (it != null) m.merge(it.getType(), it.getAmount(), Integer::sum);
        return m;
    }

    /**
     * One station at x: line hopper S (y+3, facing east), LB1 north of S reading it, LB2 under LB1, hopper X under S
     * pulling from it, a drain hopper D under X (never locked: it empties X even while X is locked), the chest under D.
     *   LB1: S holds the target and nothing else, and LB2 isn't saying "full" (7) -> 15 (lock S); otherwise 0.
     *   LB2: X is full -> 7 (tell LB1; X can't take more anyway); LB1 on -> 0 (unlock X); otherwise 15 (lock X).
     */
    void station(int x, int z, Material target) {
        hopper(sb(x, 3, z), BlockFace.EAST);
        hopper(sb(x, 2, z), BlockFace.DOWN);
        hopper(sb(x, 1, z), BlockFace.DOWN);
        sb(x, 0, z).setType(Material.CHEST);
        String id = target.getKey().getKey();
        int full = 5 * target.getMaxStackSize();
        Block lb1 = sb(x, 3, z - 1), lb2 = sb(x, 2, z - 1);
        lb1.setType(LogicItem.BLOCK);
        manager.create(lb1, new Program(List.of(rule(Action.set(15),
            Condition.items(Side.SOUTH, id, Op.GE, 1), Condition.items(Side.SOUTH, "*", Op.LE, 1), Condition.signal(Side.DOWN, Op.NE, 7))),
            Action.set(0)), OWNER, "SelfTest");
        lb2.setType(LogicItem.BLOCK);
        manager.create(lb2, new Program(List.of(
            rule(Action.set(7), Condition.items(Side.SOUTH, id, Op.GE, full)),
            rule(Action.set(0), Condition.signal(Side.UP, Op.GT, 0))), Action.set(15)), OWNER, "SelfTest");
    }

    /** Feeder chest over a hopper pushing east into the first station; overflow chest after the last. */
    Block line(int x0, int z, List<Material> targets) {
        hopper(sb(x0 - 1, 3, z), BlockFace.EAST);
        sb(x0 - 1, 4, z).setType(Material.CHEST);
        for (int i = 0; i < targets.size(); i++) station(x0 + i, z, targets.get(i));
        Block overflow = sb(x0 + targets.size(), 3, z);
        overflow.setType(Material.CHEST);
        return sb(x0 - 1, 4, z);
    }

    void sorter() {
        List<Step> parts = new ArrayList<>();
        int x0 = 300;
        for (int cx = (x0 - 16) >> 4; cx <= (x0 + 60) >> 4; cx++) for (int cz = -1; cz <= 1; cz++) w.setChunkForceLoaded(cx, cz, true);
        for (int x = x0 - 8; x <= x0 + 60; x++) for (int z = -6; z <= 8; z++) {
            w.getBlockAt(x, SY - 1, z).setType(Material.STONE);
            for (int y = SY; y < SY + 6; y++) w.getBlockAt(x, y, z).setType(Material.AIR);
        }

        // A: three stations, a mixed feed. Every item must end up in its own chest or the overflow, nothing stuck.
        List<Material> targets = List.of(Material.DIAMOND, Material.EMERALD, Material.DIAMOND_SWORD);
        int za = 0;
        Map<Material, Integer> fed = new TreeMap<>();
        long[] started = {0}, done = {-1};
        parts.add(new Step("sorter: build 3 stations, feed a mix", () -> {
            Block feeder = line(x0, za, targets);
            for (int slot = 0; slot < 27; slot++) {
                Material m = MIX.get(slot % MIX.size());
                int n = m.getMaxStackSize() == 1 ? 1 : 1 + slot % 4;
                inv(feeder).setItem(slot, new ItemStack(m, n));
                fed.merge(m, n, Integer::sum);
            }
            started[0] = w.getFullTime();
            int total = fed.values().stream().mapToInt(Integer::intValue).sum();
            for (int t = 20; t <= 1400; t += 10) getServer().getScheduler().runTaskLater(this, () -> {
                if (done[0] >= 0) return;
                int delivered = count(sb(x0 + targets.size(), 3, za), null);
                for (int i = 0; i < targets.size(); i++) delivered += count(sb(x0 + i, 0, za), null);
                if (delivered == total) done[0] = w.getFullTime() - started[0];
            }, t);
        }, 1420, () -> {
            List<String> problems = new ArrayList<>();
            for (int i = 0; i < targets.size(); i++) {
                Material t = targets.get(i);
                Map<Material, Integer> got = contents(sb(x0 + i, 0, za));
                if (!got.keySet().stream().allMatch(t::equals)) problems.add(t + " chest has " + got);
                if (got.getOrDefault(t, 0) != fed.getOrDefault(t, 0)) problems.add(t + ": " + got.getOrDefault(t, 0) + " of " + fed.get(t));
            }
            Map<Material, Integer> over = contents(sb(x0 + targets.size(), 3, za));
            for (Material t : targets) if (over.containsKey(t)) problems.add("overflow has " + over.get(t) + " " + t);
            for (int i = -1; i < targets.size(); i++) if (count(sb(x0 + i, 3, za), null) > 0) problems.add("stuck in line hopper " + i + ": " + contents(sb(x0 + i, 3, za)));
            for (int i = 0; i < targets.size(); i++) for (int y = 1; y <= 2; y++)
                if (count(sb(x0 + i, y, za), null) > 0) problems.add("left in station " + i + " hopper at +" + y + ": " + contents(sb(x0 + i, y, za)));
            int total = fed.values().stream().mapToInt(Integer::intValue).sum();
            report.add("INFO sorter: fed " + fed + "; overflow got " + over + (done[0] >= 0
                ? String.format(Locale.ROOT, "; all %d items placed in %d ticks (%.1f items/s)", total, done[0], total * 20.0 / done[0])
                : "; not all items placed"));
            return problems.isEmpty() ? null : String.join("; ", problems);
        }));

        // C: double-speed feed (a second chest over the first line hopper). Several items can sit in S at once; the
        // station usually lets its item pass, but a new item can slip into the hopper's first slot while the Logic Blocks
        // react (2-4 ticks) and be pulled instead. Reported, not failed: docs/SORTER.md says to feed through one hopper.
        int zc = 12;
        Map<Material, Integer> fedC = new TreeMap<>();
        parts.add(new Step("sorter: double-speed feed (documented limit: feed through one hopper)", () -> {
            Block feeder = line(x0, zc, targets);
            Block second = sb(x0, 4, zc);
            second.setType(Material.CHEST);
            for (Block f : List.of(feeder, second)) for (int slot = 0; slot < 27; slot++) {
                Material m = MIX.get((slot + (f == second ? 3 : 0)) % MIX.size());
                int n = m.getMaxStackSize() == 1 ? 1 : 1 + slot % 4;
                inv(f).setItem(slot, new ItemStack(m, n));
                fedC.merge(m, n, Integer::sum);
            }
        }, 1500, () -> {
            List<String> problems = new ArrayList<>();
            int sorted = 0, missed = 0;
            Map<Material, Integer> over = contents(sb(x0 + targets.size(), 3, zc));
            for (int i = 0; i < targets.size(); i++) {
                Material t = targets.get(i);
                Map<Material, Integer> got = contents(sb(x0 + i, 0, zc));
                if (!got.keySet().stream().allMatch(t::equals)) problems.add(t + " chest has " + got);
                sorted += got.getOrDefault(t, 0);
                missed += over.getOrDefault(t, 0);
            }
            report.add(String.format(Locale.ROOT, "INFO sorter double-speed feed: %d target items sorted, %d went past to the overflow (%.0f%%), wrong items: %s; fed %s",
                sorted, missed, 100.0 * missed / Math.max(1, sorted + missed), problems.isEmpty() ? "none" : String.join("; ", problems), fedC));
            return null;
        }));

        // D: a long single-hopper feed (the documented way to feed the sorter).
        int zd = 18;
        Map<Material, Integer> fedD = new TreeMap<>();
        parts.add(new Step("sorter: long single feed, no wrong items", () -> {
            Block feeder = line(x0, zd, targets);
            Block chest2 = sb(x0 - 1, 5, zd);
            // A tall feed: two chests stacked (the top one drains into the lower via a hopper).
            hopper(sb(x0 - 1, 5, zd), BlockFace.DOWN);
            chest2 = sb(x0 - 1, 6, zd);
            chest2.setType(Material.CHEST);
            for (Block f : List.of(feeder, chest2)) for (int slot = 0; slot < 27; slot++) {
                Material m = MIX.get((slot * 7 + (f == chest2 ? 5 : 0)) % MIX.size());
                int n = m.getMaxStackSize() == 1 ? 1 : 1 + (slot * 5) % 7;
                inv(f).setItem(slot, new ItemStack(m, n));
                fedD.merge(m, n, Integer::sum);
            }
        }, 2600, () -> {
            List<String> problems = new ArrayList<>();
            int sorted = 0, missed = 0, wrong = 0;
            Map<Material, Integer> over = contents(sb(x0 + targets.size(), 3, zd));
            for (int i = 0; i < targets.size(); i++) {
                Material t = targets.get(i);
                Map<Material, Integer> got = contents(sb(x0 + i, 0, zd));
                for (var e : got.entrySet()) if (e.getKey() != t) wrong += e.getValue();
                if (!got.keySet().stream().allMatch(t::equals)) problems.add(t + " chest has " + got);
                sorted += got.getOrDefault(t, 0);
                missed += over.getOrDefault(t, 0);
            }
            int total = fedD.values().stream().mapToInt(Integer::intValue).sum();
            report.add(String.format(Locale.ROOT, "INFO sorter long feed: %d items, %d sorted, %d went past, %d wrong; fed %s", total, sorted, missed, wrong, fedD));
            return problems.isEmpty() ? null : String.join("; ", problems);
        }));

        // B: the station's chest and hopper are full. Its items must pass on to the overflow instead of jamming the line.
        int zb = 6;
        parts.add(new Step("sorter: full chest passes items on, no jam", () -> {
            Block feeder = line(x0, zb, List.of(Material.DIAMOND));
            for (int slot = 0; slot < 27; slot++) inv(sb(x0, 0, zb)).setItem(slot, new ItemStack(Material.DIAMOND, 64));
            for (int y = 1; y <= 2; y++) for (int slot = 0; slot < 5; slot++) inv(sb(x0, y, zb)).setItem(slot, new ItemStack(Material.DIAMOND, 64));
            for (int slot = 0; slot < 10; slot++) inv(feeder).setItem(slot, new ItemStack(slot % 2 == 0 ? Material.DIAMOND : Material.STONE, 2));
        }, 400, () -> {
            Map<Material, Integer> over = contents(sb(x0 + 1, 3, zb));
            int stuck = count(sb(x0, 3, zb), null) + count(sb(x0 - 1, 3, zb), null) + count(sb(x0 - 1, 4, zb), null);
            report.add("INFO sorter full chest: overflow got " + over + ", still in the line " + stuck);
            return over.getOrDefault(Material.DIAMOND, 0) == 10 && over.getOrDefault(Material.STONE, 0) == 10 && stuck == 0 ? null
                : "expected 10 diamonds and 10 stone in the overflow, nothing stuck";
        }));
            // All scenarios run side by side: build them together, wait for the slowest, then check each.
        int wait = parts.stream().mapToInt(Step::ticks).max().orElse(1);
        steps.add(new Step("sorter: build " + parts.size() + " scenarios", () -> parts.forEach(p -> p.action().run()), wait, () -> null));
        for (Step p : parts) steps.add(new Step(p.name(), () -> { }, 1, p.check()));
    }

    void buildLoad() {
        List<Program.Rule> rules = new ArrayList<>();
        for (int i = 0; i < 8; i++) rules.add(rule(Action.set(i + 1),
            Condition.block(Side.NORTH, true, "gold_block"), Condition.items(Side.DOWN, "*", Op.GT, 100), Condition.signal(Side.ANY, Op.EQ, 15)));
        Program heavy = new Program(rules, Action.set(0));
        for (int i = 0; i < 400; i++) {
            int x = 92 + (i % 20) * 2, z = -19 + (i / 20) * 2;
            node(x, z, heavy);
        }
    }

    String measureLoad() {
        for (int i = 0; i < 60; i++) manager.tick();   // warm up (JIT)
        long best = Long.MAX_VALUE, total = 0;
        for (int i = 0; i < 40; i++) {
            long t = System.nanoTime();
            manager.tick();
            long dt = System.nanoTime() - t;
            total += dt; best = Math.min(best, dt);
        }
        report.add(String.format("INFO load: %d blocks, manager tick avg %.2f ms, best %.2f ms (one tick is 50 ms)",
            manager.nodes().size(), total / 40 / 1e6, best / 1e6));
        return total / 40 / 1e6 < 25 ? null : "too slow";
    }

    void run(int i) {
        if (i >= steps.size()) { finish(); return; }
        Step s = steps.get(i);
        try { s.action().run(); }
        catch (RuntimeException ex) { record(s.name(), "action threw " + ex); run(i + 1); return; }
        getServer().getScheduler().runTaskLater(this, () -> {
            String problem;
            try { problem = s.check().get(); } catch (RuntimeException ex) { problem = "check threw " + ex; }
            record(s.name(), problem);
            run(i + 1);
        }, Math.max(1, s.ticks()));
    }

    void record(String name, String problem) {
        if (problem == null) report.add("PASS " + name);
        else { failures++; report.add("FAIL " + name + ": " + problem); }
    }

    void finish() {
        report.add(failures == 0 ? "SELFTEST PASSED on " + getServer().getVersion() : "SELFTEST FAILED (" + failures + ")");
        try { getDataFolder().mkdirs(); Files.write(getDataFolder().toPath().resolve("report.txt"), report); }
        catch (IOException e) { getLogger().severe("Could not write report: " + e); }
        getLogger().info(String.join("\n", report));
        if (Boolean.getBoolean("logic.selftest.exit")) getServer().shutdown();
    }
}
