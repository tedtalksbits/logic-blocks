package dev.whizzle.logic;

import dev.whizzle.logic.model.Action;
import dev.whizzle.logic.model.Engine;
import dev.whizzle.logic.model.Program;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.AnaloguePowerable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Keeps every Logic Block in a loaded chunk, evaluates its rules on a fixed cadence, and drives the block's
 * power (a daylight detector: it powers every side at any strength 0-15 and, unlike solid blocks, doesn't pass
 * incoming power through to its other sides). Programs and state live in the chunk's persistent data, so they move with the world save and only run
 * while the chunk is loaded, exactly like redstone.
 */
public final class LogicManager implements Listener {
    /** Fewest ticks between two output changes of one block, so self-wired blocks become clocks, not lag. */
    public static final int MIN_CHANGE_TICKS = 2;

    private final JavaPlugin plugin;
    private final Map<LogicNode.Key, LogicNode> nodes = new HashMap<>();
    private final int interval, maxPerChunk;
    private long tick;
    /** Called after a node is evaluated (the bridge pushes live views). */
    private Consumer<LogicNode> evaluated = n -> { };

    public LogicManager(JavaPlugin plugin, int interval, int maxPerChunk) {
        this.plugin = plugin;
        this.interval = Math.clamp(interval, 1, 20);
        this.maxPerChunk = Math.clamp(maxPerChunk, 1, 4096);
    }

    public void onEvaluated(Consumer<LogicNode> listener) { this.evaluated = listener; }
    public long now() { return tick; }
    public Collection<LogicNode> nodes() { return Collections.unmodifiableCollection(nodes.values()); }
    public Optional<LogicNode> at(Block block) { return Optional.ofNullable(nodes.get(LogicNode.Key.of(block))); }
    public Optional<LogicNode> get(LogicNode.Key key) { return Optional.ofNullable(nodes.get(key)); }

    // ---- persistence in chunk data ----

    static NamespacedKey storageKey(int x, int y, int z) {
        return new NamespacedKey("logicblocks", "b." + (x & 15) + "." + y + "." + (z & 15));
    }

    private void save(LogicNode n) {
        Chunk chunk = n.world.getChunkAt(n.key.x() >> 4, n.key.z() >> 4);
        chunk.getPersistentDataContainer().set(storageKey(n.key.x(), n.key.y(), n.key.z()), PersistentDataType.BYTE_ARRAY, n.encode(tick));
    }

    private void erase(LogicNode n) {
        Chunk chunk = n.world.getChunkAt(n.key.x() >> 4, n.key.z() >> 4);
        chunk.getPersistentDataContainer().remove(storageKey(n.key.x(), n.key.y(), n.key.z()));
    }

    public void loadChunk(Chunk chunk) {
        PersistentDataContainer pdc = chunk.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (!key.getNamespace().equals("logicblocks") || !key.getKey().startsWith("b.")) continue;
            String[] parts = key.getKey().split("\\.");
            try {
                int x = (chunk.getX() << 4) + Integer.parseInt(parts[1]), y = Integer.parseInt(parts[2]), z = (chunk.getZ() << 4) + Integer.parseInt(parts[3]);
                byte[] data = pdc.get(key, PersistentDataType.BYTE_ARRAY);
                if (data == null) continue;
                if (chunk.getWorld().getBlockAt(x, y, z).getType() != LogicItem.BLOCK) {
                    plugin.getLogger().warning("Logic Block at " + x + " " + y + " " + z + " in " + chunk.getWorld().getName()
                        + " is no longer a Logic Block (changed outside the game?); its program was removed.");
                    pdc.remove(key);
                    continue;
                }
                LogicNode node = LogicNode.decode(chunk.getWorld(), x, y, z, data, tick);
                nodes.put(node.key, node);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Skipping unreadable Logic Block data " + key + " in chunk " + chunk.getX() + "," + chunk.getZ() + ": " + e.getMessage());
            }
        }
    }

    public void unloadChunk(Chunk chunk) {
        UUID world = chunk.getWorld().getUID();
        nodes.values().removeIf(n -> {
            if (!n.key.world().equals(world) || n.key.x() >> 4 != chunk.getX() || n.key.z() >> 4 != chunk.getZ()) return false;
            save(n);
            return true;
        });
    }

    public void loadAll() { for (World w : Bukkit.getWorlds()) for (Chunk c : w.getLoadedChunks()) loadChunk(c); }

    public void saveAll() { for (LogicNode n : nodes.values()) save(n); }

    // ---- creating, changing, removing ----

    public LogicNode create(Block block, Program program, Player owner) { return create(block, program, owner.getUniqueId(), owner.getName()); }

    public LogicNode create(Block block, Program program, UUID owner, String ownerName) {
        LogicNode node = new LogicNode(block.getWorld(), block.getX(), block.getY(), block.getZ(), program, owner, ownerName, new Engine.State());
        nodes.put(node.key, node);
        if (block.getBlockData() instanceof org.bukkit.block.data.type.DaylightDetector d && d.isInverted()) { d.setInverted(false); block.setBlockData(d, false); }
        setPower(block, 0);
        save(node);
        return node;
    }

    /** Replaces the program; state restarts (toggles off, pulses stop) so the new rules apply cleanly. */
    public void reprogram(LogicNode node, Program program) {
        node.program = program;
        node.state.matched = -2; node.state.toggled = false; node.state.pulseUntil = -1; node.state.output = 0;
        node.lastEval = Long.MIN_VALUE / 2;
        save(node);
    }

    public void remove(LogicNode node) {
        nodes.remove(node.key);
        erase(node);
    }

    public int countInChunk(Block block) {
        UUID w = block.getWorld().getUID();
        int cx = block.getX() >> 4, cz = block.getZ() >> 4;
        return (int) nodes.keySet().stream().filter(k -> k.world().equals(w) && k.x() >> 4 == cx && k.z() >> 4 == cz).count();
    }

    public int maxPerChunk() { return maxPerChunk; }

    // ---- running ----

    public void tick() {
        tick++;
        List<LogicNode> gone = null;
        for (LogicNode n : nodes.values()) {
            Block block = n.block();
            if (block.getType() != LogicItem.BLOCK) { (gone == null ? gone = new ArrayList<>() : gone).add(n); continue; }
            if (tick - n.lastEval >= interval) evaluate(n, block);
            else if (n.state.pulseUntil >= 0 && tick >= n.state.pulseUntil) { Engine.step(n.program, n.state, n.state.matched, tick); }
            apply(n, block);
        }
        if (gone != null) for (LogicNode n : gone) {
            plugin.getLogger().info("Logic Block at " + n.key.x() + " " + n.key.y() + " " + n.key.z() + " was replaced; removing its program.");
            remove(n);
        }
    }

    void evaluate(LogicNode n, Block block) {
        n.lastEval = tick;
        int beforeMatched = n.state.matched, beforeOutput = n.state.output;
        boolean beforeToggle = n.state.toggled;
        Engine.Evaluation e = Engine.evaluate(n.program, new WorldInputs(block), n.state.signals);
        Engine.remember(n.state.signals, e);
        n.last = e;
        Engine.step(n.program, n.state, e.matched(), tick);
        if (tick >= n.state.nextMove)
            for (Action a : Engine.worldActions(n.program, e))
                if (a.kind() == Action.Kind.MOVE && Mover.move(block, a)) { n.state.nextMove = tick + Mover.COOLDOWN; break; }
        if (n.state.matched != beforeMatched || n.state.output != beforeOutput || n.state.toggled != beforeToggle) save(n);
        evaluated.accept(n);
    }

    /** Drives the block toward the state's output, at most one change per MIN_CHANGE_TICKS. */
    void apply(LogicNode n, Block block) {
        int want = n.state.output;
        int actual = power(block);
        if (actual == want && n.applied == want) return;
        if (n.applied != want) {
            if (tick - n.lastChange < MIN_CHANGE_TICKS) return;
            n.applied = want;
            n.lastChange = tick;
        }
        if (actual != n.applied) setPower(block, n.applied);   // also undoes arrows and other outside changes
    }

    static int power(Block block) { return block.getBlockData() instanceof AnaloguePowerable p ? p.getPower() : 0; }

    static void setPower(Block block, int power) {
        BlockData data = block.getBlockData();
        if (!(data instanceof AnaloguePowerable p)) return;
        p.setPower(Math.clamp(power, 0, 15));
        block.setBlockData(data, true);
    }

    // ---- world events ----

    @EventHandler public void onChunkLoad(ChunkLoadEvent e) { loadChunk(e.getChunk()); }
    @EventHandler public void onChunkUnload(ChunkUnloadEvent e) { unloadChunk(e.getChunk()); }
    @EventHandler public void onWorldUnload(WorldUnloadEvent e) { for (Chunk c : e.getWorld().getLoadedChunks()) unloadChunk(c); }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent e) {
        if (!LogicItem.is(e.getItemInHand())) return;
        create(e.getBlockPlaced(), LogicItem.program(e.getItemInHand()), e.getPlayer());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPlaceCheck(BlockPlaceEvent e) {
        if (!LogicItem.is(e.getItemInHand())) return;
        Player p = e.getPlayer();
        if (!p.hasPermission("logicblocks.use")) { e.setCancelled(true); p.sendMessage(Component.text("You don't have permission to place Logic Blocks.", NamedTextColor.RED)); return; }
        if (countInChunk(e.getBlockPlaced()) >= maxPerChunk) {
            e.setCancelled(true);
            p.sendMessage(Component.text("This chunk already has " + maxPerChunk + " Logic Blocks (the server's limit).", NamedTextColor.RED));
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBreak(BlockBreakEvent e) {
        LogicNode node = nodes.get(LogicNode.Key.of(e.getBlock()));
        if (node == null) return;
        e.setDropItems(false);
        Block b = e.getBlock();
        if (e.getPlayer().getGameMode() != GameMode.CREATIVE)
            b.getWorld().dropItemNaturally(b.getLocation().add(0.5, 0.5, 0.5), LogicItem.create(node.program));
        remove(node);
    }

    private boolean isNode(Block b) { return nodes.containsKey(LogicNode.Key.of(b)); }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) { if (e.getBlocks().stream().anyMatch(this::isNode)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) { if (e.getBlocks().stream().anyMatch(this::isNode)) e.setCancelled(true); }
    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) { e.blockList().removeIf(this::isNode); }
    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) { e.blockList().removeIf(this::isNode); }
    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) { if (isNode(e.getBlock())) e.setCancelled(true); }
    /** A daylight detector recalculates from sky light every second; keep it at the Logic Block's output instead. */
    @EventHandler
    public void onRedstone(BlockRedstoneEvent e) {
        LogicNode n = nodes.get(LogicNode.Key.of(e.getBlock()));
        if (n != null) e.setNewCurrent(n.applied);
    }
}
