package dev.whizzle.logic.selftest;

import dev.whizzle.logic.Demos;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import dev.whizzle.logic.Machines;
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

        // 10. Item sorter (docs/SORTER.md, /logic demo sorter): stations of two Logic Blocks around a line of hoppers.
        sorter();

        // 11. Moving items (v2): speed, filtering, the v2 sorter, furnaces, protection plugins.
        moves();

        // 12. Turns on: a held input counts once.
        turnsOn();

        // 13. Counters and timers: the combination lock demo, a delayed off, a clock, a counter across a chunk reload.
        memory();

        // 14. Machines (/logic demo iron-crafter, rocket-crafter, super-smelter, shulker-loader, shulker-unloader), side by side.
        machines();

        // 15. Load: many busy blocks, timed.
        steps.add(new Step("load: 400 blocks x 8 rules x 3 conditions", this::buildLoad, 4, this::measureLoad));

        String only = System.getProperty("logic.selftest.only", "");
        if (!only.isBlank()) {
            List<String> prefixes = List.of(only.split(","));
            steps.removeIf(st -> prefixes.stream().noneMatch(pr -> st.name().startsWith(pr.trim())));
            report.add("INFO only steps starting with " + prefixes + " (" + steps.size() + " steps)");
        }
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

    /** The sorter exactly as /logic demo builds it (Demos.sorter); returns its feeder chest. */
    Block line(int x0, int z, List<Material> targets) {
        return Demos.sorter(manager, w, x0, SY, z, targets, OWNER, "SelfTest").feeder();
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

    // ---- moving items (v2) ----

    final Set<Block> protectedChests = new HashSet<>();

    LogicNode lb(Block b, Program p) {
        b.setType(LogicItem.BLOCK);
        return manager.create(b, p, OWNER, "SelfTest");
    }

    static Program also(Action... moves) {
        List<Program.Rule> rules = new ArrayList<>();
        for (Action a : moves) rules.add(new Program.Rule(List.of(), a));
        return Program.EMPTY.withAlso(rules);
    }

    void moves() {
        int x0 = 500;
        for (int cx = (x0 - 16) >> 4; cx <= (x0 + 80) >> 4; cx++) for (int cz = -1; cz <= 2; cz++) w.setChunkForceLoaded(cx, cz, true);
        for (int x = x0 - 8; x <= x0 + 80; x++) for (int z = -6; z <= 30; z++) {
            w.getBlockAt(x, SY - 1, z).setType(Material.STONE);
            for (int y = SY; y < SY + 6; y++) w.getBlockAt(x, y, z).setType(Material.AIR);
        }
        getServer().getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler
            public void onMove(org.bukkit.event.inventory.InventoryMoveItemEvent e) {
                Location l = e.getDestination().getLocation();
                if (l != null && protectedChests.contains(l.getBlock())) e.setCancelled(true);
            }
        }, this);
        List<Step> parts = new ArrayList<>();
        List<Material> targets = List.of(Material.DIAMOND, Material.EMERALD, Material.DIAMOND_SWORD);

        // M1: one Logic Block between two chests moves only diamonds, at hopper speed (one per 8 ticks).
        int m1 = x0;
        int[] at400 = {-1, -1};
        parts.add(new Step("move: only the named item, at hopper speed", () -> {
            // Scenarios run side by side, so read this one's count at its own 400 ticks.
            getServer().getScheduler().runTaskLater(this, () -> { at400[0] = count(sb(m1, 0, 0), Material.DIAMOND); at400[1] = count(sb(m1, 0, 0), Material.STONE); }, 400);
            sb(m1, 0, 0).setType(Material.CHEST);
            lb(sb(m1, 1, 0), also(Action.move("diamond", Side.UP, Side.DOWN)));
            sb(m1, 2, 0).setType(Material.CHEST);
            inv(sb(m1, 2, 0)).setItem(0, new ItemStack(Material.STONE, 10));
            inv(sb(m1, 2, 0)).setItem(1, new ItemStack(Material.DIAMOND, 64));
        }, 400, () -> {
            int moved = at400[0], stone = at400[1];
            report.add(String.format(Locale.ROOT, "INFO move: %d diamonds in 400 ticks (%.2f items/s; a hopper does 2.5)", moved, moved / 20.0));
            if (stone != 0) return "moved stone";
            if (count(sb(m1, 0, 0), Material.DIAMOND) != 64 || count(sb(m1, 2, 0), Material.DIAMOND) != 0) return "not all 64 diamonds arrived exactly once";
            return moved >= 45 && moved <= 51 ? null : "moved " + moved + " in 400 ticks, expected about 50 (one per 8 ticks)";
        }));

        // M2: the v2 sorter (one Logic Block per station) with the 60-item mix through one hopper.
        int m2 = x0 + 6, z2 = 0;
        Map<Material, Integer> fed2 = new TreeMap<>();
        long[] start2 = {0}, done2 = {-1};
        parts.add(new Step("move sorter: 3 stations, a mix", () -> {
            Block feeder = Demos.moveSorter(manager, w, m2, SY, z2, targets, OWNER, "SelfTest").feeder();
            for (int slot = 0; slot < 27; slot++) {
                Material m = MIX.get(slot % MIX.size());
                int n = m.getMaxStackSize() == 1 ? 1 : 1 + slot % 4;
                inv(feeder).setItem(slot, new ItemStack(m, n));
                fed2.merge(m, n, Integer::sum);
            }
            start2[0] = w.getFullTime();
            int total = fed2.values().stream().mapToInt(Integer::intValue).sum();
            for (int t = 20; t <= 1000; t += 10) getServer().getScheduler().runTaskLater(this, () -> {
                if (done2[0] >= 0) return;
                int delivered = count(sb(m2 + targets.size(), 2, z2), null);
                for (int i = 0; i < targets.size(); i++) delivered += count(sb(m2 + i, 0, z2), null);
                if (delivered == total) done2[0] = w.getFullTime() - start2[0];
            }, t);
        }, 1000, () -> {
            List<String> problems = sorted(m2, z2, targets, fed2, true);
            int total = fed2.values().stream().mapToInt(Integer::intValue).sum();
            report.add("INFO move sorter: " + (done2[0] >= 0
                ? String.format(Locale.ROOT, "all %d items placed in %d ticks (%.1f items/s)", total, done2[0], total * 20.0 / done2[0]) : "not all items placed"));
            return problems.isEmpty() ? null : String.join("; ", problems);
        }));

        // M3: double-speed feed (a second chest over the first line hopper). It can't pull a wrong item; count misses.
        int m3 = x0 + 14, z3 = 0;
        Map<Material, Integer> fed3 = new TreeMap<>();
        parts.add(new Step("move sorter: double-speed feed, never a wrong item", () -> {
            Block feeder = Demos.moveSorter(manager, w, m3, SY, z3, targets, OWNER, "SelfTest").feeder();
            Block second = sb(m3, 3, z3);
            second.setType(Material.CHEST);
            for (Block f : List.of(feeder, second)) for (int slot = 0; slot < 27; slot++) {
                Material m = MIX.get((slot + (f == second ? 3 : 0)) % MIX.size());
                int n = m.getMaxStackSize() == 1 ? 1 : 1 + slot % 4;
                inv(f).setItem(slot, new ItemStack(m, n));
                fed3.merge(m, n, Integer::sum);
            }
        }, 1500, () -> {
            List<String> problems = sorted(m3, z3, targets, fed3, false);
            int sortedN = 0, missed = 0;
            Map<Material, Integer> over = contents(sb(m3 + targets.size(), 2, z3));
            for (int i = 0; i < targets.size(); i++) { sortedN += count(sb(m3 + i, 0, z3), targets.get(i)); missed += over.getOrDefault(targets.get(i), 0); }
            report.add(String.format(Locale.ROOT, "INFO move sorter double-speed feed: %d sorted, %d went past (%.0f%%), wrong items: %s",
                sortedN, missed, 100.0 * missed / Math.max(1, sortedN + missed), problems.isEmpty() ? "none" : String.join("; ", problems)));
            return problems.isEmpty() ? null : String.join("; ", problems);
        }));

        // M4: a full chest: the move can't happen, so the item rides on to the overflow; nothing jams.
        int m4 = x0 + 22, z4 = 0;
        parts.add(new Step("move sorter: full chest passes items on, no jam", () -> {
            Block feeder = Demos.moveSorter(manager, w, m4, SY, z4, List.of(Material.DIAMOND), OWNER, "SelfTest").feeder();
            for (int slot = 0; slot < 27; slot++) inv(sb(m4, 0, z4)).setItem(slot, new ItemStack(Material.DIAMOND, 64));
            for (int slot = 0; slot < 10; slot++) inv(feeder).setItem(slot, new ItemStack(slot % 2 == 0 ? Material.DIAMOND : Material.STONE, 2));
        }, 400, () -> {
            Map<Material, Integer> over = contents(sb(m4 + 1, 2, z4));
            int stuck = count(sb(m4, 2, z4), null) + count(sb(m4 - 1, 2, z4), null) + count(sb(m4 - 1, 3, z4), null);
            return over.getOrDefault(Material.DIAMOND, 0) == 10 && over.getOrDefault(Material.STONE, 0) == 10 && stuck == 0 ? null
                : "overflow got " + over + ", still in the line " + stuck;
        }));

        // M5: furnaces, through the faces a hopper would use.
        int m5 = x0 + 30;
        parts.add(new Step("move: furnace side takes fuel only, top takes smelting input, bottom gives results", () -> {
            // A: Logic Block west of a furnace, chest above it: coal goes into the fuel slot, iron ore is refused.
            sb(m5, 2, 0).setType(Material.CHEST);
            inv(sb(m5, 2, 0)).setItem(0, new ItemStack(Material.IRON_ORE, 3));
            inv(sb(m5, 2, 0)).setItem(1, new ItemStack(Material.COAL, 3));
            lb(sb(m5, 1, 0), also(Action.move("iron_ore", Side.UP, Side.EAST), Action.move("coal", Side.UP, Side.EAST)));
            sb(m5 + 1, 1, 0).setType(Material.FURNACE);
            // B: Logic Block on top of a furnace, chest north of it: iron ore goes into the smelting slot.
            sb(m5, 1, 4).setType(Material.FURNACE);
            lb(sb(m5, 2, 4), also(Action.move("iron_ore", Side.NORTH, Side.DOWN)));
            sb(m5, 2, 3).setType(Material.CHEST);
            inv(sb(m5, 2, 3)).setItem(0, new ItemStack(Material.IRON_ORE, 2));
            // C: Logic Block under a furnace, chest under it: takes the result, leaves the fuel.
            sb(m5, 0, 8).setType(Material.CHEST);
            lb(sb(m5, 1, 8), also(Action.move("*", Side.UP, Side.DOWN)));
            sb(m5, 2, 8).setType(Material.FURNACE);
            org.bukkit.inventory.FurnaceInventory f = (org.bukkit.inventory.FurnaceInventory) inv(sb(m5, 2, 8));
            f.setResult(new ItemStack(Material.IRON_INGOT, 3));
            f.setFuel(new ItemStack(Material.COAL, 2));
        }, 100, () -> {
            org.bukkit.inventory.FurnaceInventory a = (org.bukkit.inventory.FurnaceInventory) inv(sb(m5 + 1, 1, 0));
            org.bukkit.inventory.FurnaceInventory b = (org.bukkit.inventory.FurnaceInventory) inv(sb(m5, 1, 4));
            org.bukkit.inventory.FurnaceInventory c = (org.bukkit.inventory.FurnaceInventory) inv(sb(m5, 2, 8));
            List<String> problems = new ArrayList<>();
            if (a.getFuel() == null || a.getFuel().getType() != Material.COAL || a.getFuel().getAmount() != 3) problems.add("side: fuel slot " + a.getFuel());
            if (a.getSmelting() != null && !a.getSmelting().getType().isAir()) problems.add("side: smelting slot got " + a.getSmelting());
            if (count(sb(m5, 2, 0), Material.IRON_ORE) != 3) problems.add("side: iron ore left the chest");
            if (b.getSmelting() == null || b.getSmelting().getType() != Material.IRON_ORE || b.getSmelting().getAmount() != 2) problems.add("top: smelting slot " + b.getSmelting());
            if (count(sb(m5, 0, 8), Material.IRON_INGOT) != 3) problems.add("bottom: got " + contents(sb(m5, 0, 8)));
            if (c.getFuel() == null || c.getFuel().getAmount() != 2) problems.add("bottom: took fuel " + c.getFuel());
            return problems.isEmpty() ? null : String.join("; ", problems);
        }));

        // M6: a protection plugin cancelling the hopper move event stops the Logic Block too.
        int m6 = x0 + 36;
        parts.add(new Step("move: a protection plugin can stop it (InventoryMoveItemEvent)", () -> {
            sb(m6, 0, 0).setType(Material.CHEST);
            protectedChests.add(sb(m6, 0, 0));
            lb(sb(m6, 1, 0), also(Action.move("*", Side.UP, Side.DOWN)));
            sb(m6, 2, 0).setType(Material.CHEST);
            inv(sb(m6, 2, 0)).setItem(0, new ItemStack(Material.DIAMOND, 5));
        }, 60, () -> count(sb(m6, 0, 0), null) == 0 && count(sb(m6, 2, 0), Material.DIAMOND) == 5 ? null : "moved into a protected chest"));

        int wait = parts.stream().mapToInt(Step::ticks).max().orElse(1);
        steps.add(new Step("moves: build " + parts.size() + " scenarios", () -> parts.forEach(p -> p.action().run()), wait, () -> null));
        for (Step p : parts) steps.add(new Step(p.name(), () -> { }, 1, p.check()));
    }

    /** Problems with a sorter line's chests: wrong items; with {@code all}, also targets missing from their chest. */
    List<String> sorted(int x0, int z, List<Material> targets, Map<Material, Integer> fed, boolean all) {
        List<String> problems = new ArrayList<>();
        for (int i = 0; i < targets.size(); i++) {
            Material t = targets.get(i);
            Map<Material, Integer> got = contents(sb(x0 + i, 0, z));
            if (!got.keySet().stream().allMatch(t::equals)) problems.add(t + " chest has " + got);
            if (all && got.getOrDefault(t, 0) != fed.getOrDefault(t, 0)) problems.add(t + ": " + got.getOrDefault(t, 0) + " of " + fed.get(t));
        }
        if (all) {
            Map<Material, Integer> over = contents(sb(x0 + targets.size(), 2, z));
            for (Material t : targets) if (over.containsKey(t)) problems.add("overflow has " + over.get(t) + " " + t);
            for (int i = -1; i < targets.size(); i++) if (count(sb(x0 + i, 2, z), null) > 0) problems.add("stuck in line hopper " + i);
        }
        return problems;
    }

    // ---- turns on ----

    void turnsOn() {
        int t = 620;
        for (int cx = (t - 8) >> 4; cx <= (t + 8) >> 4; cx++) w.setChunkForceLoaded(cx, 0, true);
        steps.add(new Step("turns on: placed", () -> {
            for (int x = t - 4; x <= t + 4; x++) { w.getBlockAt(x, Y - 1, 0).setType(Material.STONE); w.getBlockAt(x, Y, 0).setType(Material.AIR); }
            node(t, 0, new Program(List.of(rule(Action.toggle(15), Condition.change(Side.WEST, true))), Action.keep()));
            repeaterInto(t - 1, 0, BlockFace.WEST);
            at(t + 1, 0).setType(Material.REDSTONE_WIRE);
        }, 6, () -> expect("wire", wire(t + 1, 0), 0)));
        for (int press = 1; press <= 3; press++) {
            int want = press % 2 == 1 ? 15 : 0;
            steps.add(new Step("turns on: press " + press + " and hold -> " + want, () -> at(t - 2, 0).setType(Material.REDSTONE_BLOCK), 20,
                () -> expect("wire", wire(t + 1, 0), want)));
            steps.add(new Step("turns on: release " + press + " -> still " + want, () -> at(t - 2, 0).setType(Material.AIR), 10,
                () -> expect("wire", wire(t + 1, 0), want)));
        }
    }

    // ---- counters and timers ----

    /** Presses a button like a player would (powered, then released {@code ticks} later). */
    void press(Block button, int ticks) {
        org.bukkit.block.data.type.Switch sw = (org.bukkit.block.data.type.Switch) button.getBlockData();
        sw.setPowered(true);
        button.setBlockData(sw, true);
        getServer().getScheduler().runTaskLater(this, () -> {
            org.bukkit.block.data.type.Switch up = (org.bukkit.block.data.type.Switch) button.getBlockData();
            up.setPowered(false);
            button.setBlockData(up, true);
        }, ticks);
    }

    static boolean open(Block door) { return door.getBlockData() instanceof org.bukkit.block.data.Openable o && o.isOpen(); }

    void memory() {
        int lx = 660, dx = 680, cx = 690, px = 776;   // px: a chunk well away from the others, so it can unload
        for (int c = 640 >> 4; c <= 700 >> 4; c++) for (int cz = -1; cz <= 0; cz++) w.setChunkForceLoaded(c, cz, true);
        w.setChunkForceLoaded(px >> 4, 0, true);
        Demos.Lock[] lock = new Demos.Lock[1];
        steps.add(new Step("lock: built (/logic demo lock)", () -> {
            for (int x = 650; x <= 700; x++) for (int z = -4; z <= 3; z++) {
                w.getBlockAt(x, Y - 1, z).setType(Material.STONE);
                for (int y = Y; y < Y + 3; y++) w.getBlockAt(x, y, z).setType(Material.AIR);
            }
            for (int x = px - 3; x <= px + 3; x++) { w.getBlockAt(x, Y - 1, 0).setType(Material.STONE); w.getBlockAt(x, Y, 0).setType(Material.AIR); }
            if (Demos.lockBlocked(w, lx, Y, 0) != null) throw new IllegalStateException("lock space not clear");
            lock[0] = Demos.lock(manager, w, lx, Y, 0, OWNER, "SelfTest");
        }, 6, () -> open(lock[0].door()) ? "door open before any press" : null));
        Supplier<Integer> a = () -> manager.at(lock[0].logic()).orElseThrow().state().counters[0];
        steps.add(new Step("lock: press 1 -> A = 1", () -> press(lock[0].buttons().get(0), 20), 30, () -> expect("A", a.get(), 1)));
        steps.add(new Step("lock: press 2 -> A = 2, still shut", () -> press(lock[0].buttons().get(1), 20), 30,
            () -> open(lock[0].door()) ? "door open early" : expect("A", a.get(), 2)));
        steps.add(new Step("lock: press 3 -> door opens, A back to 0", () -> press(lock[0].buttons().get(2), 20), 10,
            () -> !open(lock[0].door()) ? "door shut" : expect("A", a.get(), 0)));
        steps.add(new Step("lock: still open 2 seconds later", () -> { }, 40, () -> open(lock[0].door()) ? null : "door shut early"));
        steps.add(new Step("lock: shut after 3 seconds", () -> { }, 30, () -> open(lock[0].door()) ? "door still open" : null));
        steps.add(new Step("lock: wrong order (1, 3, 2) stays shut", () -> {
            press(lock[0].buttons().get(0), 20);
            getServer().getScheduler().runTaskLater(this, () -> press(lock[0].buttons().get(2), 20), 30);
            getServer().getScheduler().runTaskLater(this, () -> press(lock[0].buttons().get(1), 20), 60);
        }, 100, () -> open(lock[0].door()) ? "door opened" : expect("A", a.get(), 0)));
        steps.add(new Step("lock: right order after a wrong one opens", () -> {
            for (int i = 0; i < 3; i++) { int b = i; getServer().getScheduler().runTaskLater(this, () -> press(lock[0].buttons().get(b), 20), 1 + 30L * i); }
        }, 70, () -> open(lock[0].door()) ? null : "door shut"));

        // Delayed off: on while the input is, and 40 ticks after (a timer started when the input goes off).
        steps.add(new Step("delayed off: input on -> 15", () -> {
            node(dx, 0, Demos.delayedOff(40));
            repeaterInto(dx - 1, 0, BlockFace.WEST);
            at(dx + 1, 0).setType(Material.REDSTONE_WIRE);
            at(dx - 2, 0).setType(Material.REDSTONE_BLOCK);
        }, 10, () -> expect("wire", wire(dx + 1, 0), 15)));
        steps.add(new Step("delayed off: input off, still on 30 ticks later", () -> at(dx - 2, 0).setType(Material.AIR), 30,
            () -> expect("wire", wire(dx + 1, 0), 15)));
        steps.add(new Step("delayed off: off by 50 ticks", () -> { }, 20, () -> expect("wire", wire(dx + 1, 0), 0)));

        // Clock: a 4-tick pulse every 20 ticks from a timer that restarts itself.
        List<Long> rises = new ArrayList<>();
        steps.add(new Step("clock: a pulse every 20 ticks", () -> {
            node(cx, 0, Demos.clock(20, 4));
            at(cx + 1, 0).setType(Material.REDSTONE_WIRE);
            int[] last = {0};
            for (int t = 1; t <= 220; t++) getServer().getScheduler().runTaskLater(this, () -> {
                int now = wire(cx + 1, 0);
                if (now > 0 && last[0] == 0) rises.add(manager.now());
                last[0] = now;
            }, t);
        }, 225, () -> {
            report.add("INFO clock rises at " + rises);
            if (rises.size() < 9 || rises.size() > 12) return rises.size() + " pulses in 220 ticks";
            for (int i = 1; i < rises.size(); i++) if (rises.get(i) - rises.get(i - 1) != 20) return "gap " + (rises.get(i) - rises.get(i - 1));
            return null;
        }));

        // A counter keeps its value when its chunk unloads and loads again.
        Program twoPresses = new Program(List.of(rule(Action.set(15), Condition.counter("a", Op.GE, 2))), Action.set(0),
            List.of(new Program.Rule(List.of(Condition.change(Side.WEST, true)), Action.add("a", 1))));
        steps.add(new Step("counter persistence: one press", () -> {
            node(px, 0, twoPresses);
            repeaterInto(px - 1, 0, BlockFace.WEST);
            at(px + 1, 0).setType(Material.REDSTONE_WIRE);
            getServer().getScheduler().runTaskLater(this, () -> at(px - 2, 0).setType(Material.REDSTONE_BLOCK), 6);
            getServer().getScheduler().runTaskLater(this, () -> at(px - 2, 0).setType(Material.AIR), 16);
        }, 24, () -> expect("A", manager.at(at(px, 0)).orElseThrow().state().counters[0], 1)));
        steps.add(new Step("counter persistence: release chunk", () -> w.setChunkForceLoaded(px >> 4, 0, false), 2, () -> null));
        steps.add(new Step("counter persistence: chunk unloaded", () -> {
            if (!w.unloadChunk(px >> 4, 0, true)) report.add("INFO chunk refused to unload");
        }, 4, () -> w.isChunkLoaded(px >> 4, 0) ? "chunk still loaded" : null));
        steps.add(new Step("counter persistence: reloaded, A still 1, output 0", () -> w.setChunkForceLoaded(px >> 4, 0, true), 8, () -> {
            LogicNode n = manager.at(at(px, 0)).orElse(null);
            if (n == null) return "not registered after reload";
            return n.state().counters[0] != 1 ? "A = " + n.state().counters[0] : expect("wire", wire(px + 1, 0), 0);
        }));
        steps.add(new Step("counter persistence: second press -> 15", () -> {
            at(px - 2, 0).setType(Material.REDSTONE_BLOCK);
            getServer().getScheduler().runTaskLater(this, () -> at(px - 2, 0).setType(Material.AIR), 10);
        }, 16, () -> expect("wire", wire(px + 1, 0), 15)));
    }

    // ---- machines ----

    /** Items in a container, including what's inside shulker box items (and how many boxes, under SHULKER_BOX). */
    static Map<Material, Integer> deep(Block b) {
        Map<Material, Integer> m = new TreeMap<>();
        for (ItemStack it : inv(b).getContents()) {
            if (it == null) continue;
            m.merge(it.getType(), it.getAmount(), Integer::sum);
        }
        return m;
    }

    /** Item counts inside each shulker box item in {@code b}, one list entry per box. */
    static List<Integer> boxCounts(Block b) {
        List<Integer> out = new ArrayList<>();
        for (ItemStack it : inv(b).getContents()) {
            if (it == null || !(it.getItemMeta() instanceof org.bukkit.inventory.meta.BlockStateMeta meta) || !(meta.getBlockState() instanceof org.bukkit.block.ShulkerBox box)) continue;
            int n = 0;
            for (ItemStack in : box.getInventory().getContents()) if (in != null) n += in.getAmount();
            for (int i = 0; i < it.getAmount(); i++) out.add(n);
        }
        return out;
    }

    void machines() {
        int x0 = 900;   // well away from the counter-persistence chunk, so that one can unload
        for (int c = (x0 - 8) >> 4; c <= (x0 + 72) >> 4; c++) for (int cz = -1; cz <= 1; cz++) w.setChunkForceLoaded(c, cz, true);
        Map<Machines.Kind, Machines.Built> built = new java.util.EnumMap<>(Machines.Kind.class);
        int[] xs = {x0, x0 + 10, x0 + 20, x0 + 40, x0 + 50};
        Set<Integer> furnacesUsed = new TreeSet<>();
        steps.add(new Step("machines: build all five, stocked as /logic demo does", () -> {
            for (int x = x0 - 4; x <= x0 + 64; x++) for (int z = -4; z <= 6; z++) {
                w.getBlockAt(x, Y - 1, z).setType(Material.STONE);
                for (int y = Y; y < Y + 8; y++) w.getBlockAt(x, y, z).setType(Material.AIR);
            }
            Machines.Kind[] kinds = Machines.Kind.values();
            for (int i = 0; i < kinds.length; i++) {
                int n = kinds[i] == Machines.Kind.ROCKET_CRAFTER ? 3 : Machines.MAX_FURNACES;
                if (Machines.blocked(w, xs[i], Y, 0, kinds[i], n) != null) throw new IllegalStateException(kinds[i] + " space not clear");
                built.put(kinds[i], Machines.build(manager, w, xs[i], Y, 0, kinds[i], n, OWNER, "SelfTest"));
            }
            // Which furnaces get work: the smelter should spread it, not use the first few.
            for (int t = 5; t < 600; t += 5) getServer().getScheduler().runTaskLater(this, () -> {
                for (int i = 0; i < Machines.MAX_FURNACES; i++)
                    if (count(w.getBlockAt(xs[2] + 1 + i, Y + 2, 0), Material.RAW_IRON) > 0) furnacesUsed.add(i);
            }, t);
        }, 400, () -> {
            // Loader after 400 ticks: the first box (27 tools) is full and gone to the output chest; a new one is in place.
            Machines.Built l = built.get(Machines.Kind.SHULKER_LOADER);
            Block spot = w.getBlockAt(xs[3] + 1, Y + 1, 2);
            report.add("INFO shulker loader after 400 ticks: output " + deep(l.output()) + ", boxes " + boxCounts(l.output()) + ", spot " + spot.getType()
                + ", input " + deep(l.inputs().get(1)) + ", logic " + l.logic().stream().map(b -> manager.at(b).map(n -> n.output() + " "
                + (n.last() == null ? "" : java.util.Arrays.deepToString(n.last().readings()))).orElse("?")).toList()
                + ", items around " + spot.getLocation().getNearbyEntities(4, 4, 4).stream().map(e -> e.getType() + "@" + e.getLocation().getBlockX() + "," + e.getLocation().getBlockY() + "," + e.getLocation().getBlockZ()).toList()
                + ", in the box " + (spot.getState() instanceof org.bukkit.block.ShulkerBox sb ? deep(spot) : "-") + ", feeder " + deep(l.inputs().get(0)) + ", dispenser " + deep(w.getBlockAt(xs[3] + 1, Y + 2, 2)));
            if (!boxCounts(l.output()).equals(List.of(27))) return "expected one box of 27 in the output chest, got " + boxCounts(l.output());
            if (!org.bukkit.Tag.SHULKER_BOXES.isTagged(spot.getType())) return "no new box placed: " + spot.getType();
            if (count(l.inputs().get(1), null) != 0) return "input chest not empty";
            return null;
        }));
        steps.add(new Step("shulker loader: 27 more tools fill a second box", () -> {
            Inventory in = Machines.inv(built.get(Machines.Kind.SHULKER_LOADER).inputs().get(1));
            for (int i = 0; i < 27; i++) in.setItem(i, new ItemStack(Machines.TOOLS.get((i + 1) % Machines.TOOLS.size())));
        }, 400, () -> {
            Machines.Built l = built.get(Machines.Kind.SHULKER_LOADER);
            Map<Material, Integer> out = deep(l.output());
            if (!boxCounts(l.output()).equals(List.of(27, 27))) return "expected two boxes of 27, got " + boxCounts(l.output()) + " " + out;
            if (out.keySet().stream().anyMatch(m -> !org.bukkit.Tag.SHULKER_BOXES.isTagged(m))) return "loose items leaked into the output: " + out;
            return null;
        }));
        steps.add(new Step("iron crafter: 81 ingots -> 9 iron blocks", () -> { }, 1, () -> {
            Machines.Built b = built.get(Machines.Kind.IRON_CRAFTER);
            Map<Material, Integer> out = deep(b.output());
            int left = count(b.inputs().get(0), null) + count(w.getBlockAt(xs[0] + 1, Y, 0), null);
            return out.equals(Map.of(Material.IRON_BLOCK, 9)) && left == 0 ? null : "output " + out + ", ingots left " + left;
        }));
        steps.add(new Step("rocket crafter: 6 paper + 18 gunpowder -> 18 flight-3 rockets", () -> { }, 1, () -> {
            Machines.Built b = built.get(Machines.Kind.ROCKET_CRAFTER);
            Map<Material, Integer> out = deep(b.output());
            if (!out.equals(Map.of(Material.FIREWORK_ROCKET, 18))) return "output " + out + ", input left " + deep(b.inputs().get(0));
            for (ItemStack it : inv(b.output()).getContents())
                if (it != null && it.getItemMeta() instanceof org.bukkit.inventory.meta.FireworkMeta fm && fm.getPower() != 3) return "flight " + fm.getPower();
            return null;
        }));
        steps.add(new Step("super smelter: 16 raw iron -> 16 iron ingots, spread over the furnaces", () -> { }, 1, () -> {
            Machines.Built b = built.get(Machines.Kind.SUPER_SMELTER);
            Map<Material, Integer> out = deep(b.output());
            report.add("INFO super smelter: output " + out + ", furnaces used " + furnacesUsed);
            if (!out.equals(Map.of(Material.IRON_INGOT, 16))) return "output " + out;
            return furnacesUsed.size() >= 6 ? null : "only furnaces " + furnacesUsed + " got work";
        }));
        steps.add(new Step("shulker unloader: 3 boxes emptied, items and empty boxes out", () -> { }, 1, () -> {
            Machines.Built b = built.get(Machines.Kind.SHULKER_UNLOADER);
            Map<Material, Integer> out = deep(b.output());
            report.add("INFO shulker unloader: output " + out + ", boxes " + boxCounts(b.output()));
            Block spot = w.getBlockAt(xs[4] + 1, Y + 1, 2);
            report.add("INFO shulker unloader: spot " + spot.getType() + ", dispenser " + deep(w.getBlockAt(xs[4] + 1, Y + 2, 2)) + ", feeder " + deep(b.inputs().get(0))
                + ", items around " + spot.getLocation().getNearbyEntities(4, 4, 4).stream().map(e -> e.getType() + "@" + e.getLocation().getBlockX() + "," + e.getLocation().getBlockY() + "," + e.getLocation().getBlockZ()
                + (e instanceof org.bukkit.entity.Item it ? ":" + it.getItemStack().getType() : "")).toList()
                + ", brain " + manager.at(w.getBlockAt(xs[4] + 2, Y + 1, 2)).map(n -> n.output()).orElse(-9));
            Map<Material, Integer> want = new TreeMap<>();
            for (int i = 0; i < 3; i++) for (int t = 0; t < 6; t++) want.merge(Machines.TOOLS.get((t + i) % Machines.TOOLS.size()), 1, Integer::sum);
            want.put(Material.COBBLESTONE, 24); want.put(Material.OAK_LOG, 24); want.put(Material.SHULKER_BOX, 3);
            if (!out.equals(want)) return "output " + out + ", expected " + want;
            return boxCounts(b.output()).equals(List.of(0, 0, 0)) ? null : "boxes not empty: " + boxCounts(b.output());
        }));
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
