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

        // 10. Load: many busy blocks, timed.
        steps.add(new Step("load: 400 blocks x 8 rules x 3 conditions", this::buildLoad, 4, this::measureLoad));

        run(0);
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
