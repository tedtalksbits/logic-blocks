package dev.whizzle.logic;

import dev.whizzle.logic.model.Program;
import dev.whizzle.logic.model.Wire;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.util.*;

/**
 * Server half of the client-mod protocol (docs/PROTOCOL.md): right-clicking a Logic Block opens the editor, the
 * client streams nothing but edits back, and every save is validated here.
 */
public final class ClientBridge implements PluginMessageListener, Listener {
    public static final int PROTOCOL = 1;
    public static final String HELLO = "logicblocks:hello", WELCOME = "logicblocks:welcome", OPEN = "logicblocks:open",
        SAVE = "logicblocks:save", RESULT = "logicblocks:result", LIVE = "logicblocks:live", CLOSE = "logicblocks:close";
    public static final List<String> INCOMING = List.of(HELLO, SAVE, CLOSE);
    public static final List<String> OUTGOING = List.of(WELCOME, OPEN, RESULT, LIVE);
    /** Editing reach, in blocks from the block's centre. */
    public static final double REACH = 8;
    private static final int REQUESTS_PER_SECOND = 10;

    public interface Outbox { void send(Player player, String channel, byte[] data); }

    private final LogicManager manager;
    private final Outbox outbox;
    private final boolean anyoneEdits;
    private final Map<UUID, LogicNode.Key> watching = new HashMap<>();
    private final Map<UUID, long[]> rate = new HashMap<>();

    private final java.util.function.Function<LogicNode, dev.whizzle.logic.model.Snapshot> sensors;

    public ClientBridge(LogicManager manager, Outbox outbox, boolean anyoneEdits) {
        this(manager, outbox, anyoneEdits, n -> new WorldInputs(n.block()).snapshot(n.output(), n.last == null ? -1 : n.last.matched()));
    }

    /** {@code sensors} reads what a block sees; tests substitute it where the mock server lacks redstone. */
    public ClientBridge(LogicManager manager, Outbox outbox, boolean anyoneEdits, java.util.function.Function<LogicNode, dev.whizzle.logic.model.Snapshot> sensors) {
        this.sensors = sensors;
        this.manager = manager;
        this.outbox = outbox;
        this.anyoneEdits = anyoneEdits;
    }

    public boolean canEdit(Player p, LogicNode n) {
        if (p.hasPermission("logicblocks.admin")) return true;
        if (!p.hasPermission("logicblocks.use")) return false;
        return anyoneEdits || n.owner().equals(p.getUniqueId());
    }

    public static boolean hasMod(Player p) { return p.getListeningPluginChannels().contains(OPEN); }

    // ---- opening ----

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        Optional<LogicNode> node = manager.at(e.getClickedBlock());
        if (node.isEmpty()) return;
        Player p = e.getPlayer();
        // Sneak + block in hand places against the Logic Block, like any block with a screen.
        if (p.isSneaking() && e.getItem() != null && e.getItem().getType().isBlock() && e.getItem().getType() != Material.AIR) return;
        e.setCancelled(true);
        if (hasMod(p)) open(p, node.get());
        else describe(p, node.get());
    }

    void open(Player p, LogicNode n) {
        watching.put(p.getUniqueId(), n.key);
        Wire.Writer out = new Wire.Writer().int32(n.key.x()).int32(n.key.y()).int32(n.key.z()).bool(canEdit(p, n)).utf(n.ownerName())
            .bytes(n.program().encode()).bytes(snapshot(n));
        outbox.send(p, OPEN, out.bytes());
    }

    void describe(Player p, LogicNode n) {
        p.sendMessage(Component.text("Logic Block by " + n.ownerName() + " (output " + n.output() + "):", NamedTextColor.AQUA));
        for (String line : n.program().describe()) p.sendMessage(Component.text("  " + line, NamedTextColor.GRAY));
        p.sendMessage(Component.text("Install the Logic Blocks client mod to edit it.", NamedTextColor.YELLOW));
    }

    private byte[] snapshot(LogicNode n) { return sensors.apply(n).encode(); }

    // ---- live view ----

    /** Runs every few ticks: sends each watcher a fresh snapshot, and stops watching when they walk away. */
    public void pushLive() {
        Iterator<Map.Entry<UUID, LogicNode.Key>> it = watching.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, LogicNode.Key> e = it.next();
            Player p = org.bukkit.Bukkit.getPlayer(e.getKey());
            LogicNode n = manager.get(e.getValue()).orElse(null);
            if (p == null || n == null || !p.getWorld().equals(n.world) || p.getLocation().distance(n.center()) > REACH + 8) { it.remove(); continue; }
            outbox.send(p, LIVE, new Wire.Writer().int32(n.key.x()).int32(n.key.y()).int32(n.key.z()).bytes(snapshot(n)).bytes());
        }
    }

    public int watchers() { return watching.size(); }

    // ---- messages ----

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!allow(player)) return;
        try {
            Wire.Reader in = new Wire.Reader(message);
            switch (channel) {
                case HELLO -> { in.varInt(); in.end(); outbox.send(player, WELCOME, new Wire.Writer().varInt(PROTOCOL).bytes()); }
                case CLOSE -> { in.end(); watching.remove(player.getUniqueId()); }
                case SAVE -> {
                    int x = in.int32(), y = in.int32(), z = in.int32();
                    byte[] program = in.bytes(Program.MAX_BYTES);
                    in.end();
                    save(player, x, y, z, program);
                }
                default -> { }
            }
        } catch (IllegalArgumentException e) {
            result(player, false, "Bad request from client: " + e.getMessage());
        }
    }

    void save(Player p, int x, int y, int z, byte[] data) {
        Block block = p.getWorld().getBlockAt(x, y, z);
        LogicNode n = manager.at(block).orElse(null);
        if (n == null) { result(p, false, "That Logic Block is gone."); return; }
        if (!canEdit(p, n)) { result(p, false, "Only " + n.ownerName() + " or an operator can change this Logic Block."); return; }
        if (p.getLocation().distance(n.center()) > REACH) { result(p, false, "Move closer to the Logic Block to save."); return; }
        Program program;
        try { program = Program.decode(data); }
        catch (IllegalArgumentException e) { result(p, false, "Couldn't read those rules: " + e.getMessage()); return; }
        String problem = program.problem();
        if (problem != null) { result(p, false, problem); return; }
        manager.reprogram(n, program);
        result(p, true, "Saved " + program.rules().size() + " rule" + (program.rules().size() == 1 ? "" : "s") + ".");
    }

    private void result(Player p, boolean ok, String message) {
        outbox.send(p, RESULT, new Wire.Writer().bool(ok).utf(message).bytes());
    }

    private boolean allow(Player p) {
        long now = System.currentTimeMillis() / 1000;
        long[] w = rate.computeIfAbsent(p.getUniqueId(), k -> new long[]{now, 0});
        if (w[0] != now) { w[0] = now; w[1] = 0; }
        return ++w[1] <= REQUESTS_PER_SECOND;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { watching.remove(e.getPlayer().getUniqueId()); rate.remove(e.getPlayer().getUniqueId()); }
}
