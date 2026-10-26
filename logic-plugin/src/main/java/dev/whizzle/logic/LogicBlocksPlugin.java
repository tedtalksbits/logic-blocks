package dev.whizzle.logic;

import dev.whizzle.logic.model.Program;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;

public class LogicBlocksPlugin extends JavaPlugin implements Listener, TabExecutor {
    private LogicManager manager;
    private ClientBridge bridge;
    private BukkitTask tick, live;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();
        manager = new LogicManager(this, getConfig().getInt("check-interval-ticks", 2), getConfig().getInt("max-per-chunk", 64));
        bridge = new ClientBridge(manager, (p, channel, data) -> p.sendPluginMessage(this, channel, data),
            "anyone".equalsIgnoreCase(getConfig().getString("editing", "owner")));
        getServer().getPluginManager().registerEvents(manager, this);
        getServer().getPluginManager().registerEvents(bridge, this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String c : ClientBridge.INCOMING) getServer().getMessenger().registerIncomingPluginChannel(this, c, bridge);
        for (String c : ClientBridge.OUTGOING) getServer().getMessenger().registerOutgoingPluginChannel(this, c);
        LogicItem.registerRecipe(this);
        PluginCommand command = getCommand("logic");
        if (command == null) throw new IllegalStateException("logic command missing from plugin.yml");
        command.setExecutor(this);
        command.setTabCompleter(this);
        Signals.init(getServer().getWorlds().getFirst(), getLogger());
        manager.loadAll();
        tick = getServer().getScheduler().runTaskTimer(this, manager::tick, 1, 1);
        live = getServer().getScheduler().runTaskTimer(this, bridge::pushLive, 4, 4);
        getLogger().info("Logic Blocks enabled: " + manager.nodes().size() + " loaded.");
    }

    @Override
    public void onDisable() {
        if (tick != null) tick.cancel();
        if (live != null) live.cancel();
        if (manager != null) manager.saveAll();
        Bukkit.removeRecipe(LogicItem.RECIPE_KEY);
    }

    public LogicManager manager() { return manager; }
    public ClientBridge bridge() { return bridge; }

    @EventHandler public void onJoin(PlayerJoinEvent e) { e.getPlayer().discoverRecipe(LogicItem.RECIPE_KEY); }

    @EventHandler public void onReloaded(ServerResourcesReloadedEvent e) {
        if (Bukkit.getRecipe(LogicItem.RECIPE_KEY) != null) return;
        LogicItem.registerRecipe(this);
        for (Player p : Bukkit.getOnlinePlayers()) p.discoverRecipe(LogicItem.RECIPE_KEY);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "give" -> {
                if (!sender.hasPermission("logicblocks.admin")) { sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED)); return true; }
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) { sender.sendMessage(Component.text("Unknown player.", NamedTextColor.RED)); return true; }
                int amount = args.length > 2 ? Math.clamp(parse(args[2]), 1, 64) : 1;
                var item = LogicItem.create(Program.EMPTY);
                item.setAmount(amount);
                target.getInventory().addItem(item).values().forEach(left -> target.getWorld().dropItem(target.getLocation(), left));
                sender.sendMessage(Component.text("Gave " + amount + " Logic Block" + (amount == 1 ? "" : "s") + " to " + target.getName() + ".", NamedTextColor.GREEN));
            }
            case "info" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
                Block b = p.getTargetBlockExact(8);
                LogicNode n = b == null ? null : manager.at(b).orElse(null);
                if (n == null) { p.sendMessage(Component.text("Look at a Logic Block (within 8 blocks).", NamedTextColor.RED)); return true; }
                bridge.describe(p, n);
            }
            case "demo" -> demo(sender, args);
            case "status" -> {
                if (!sender.hasPermission("logicblocks.admin")) { sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED)); return true; }
                sender.sendMessage(Component.text(manager.nodes().size() + " Logic Blocks loaded, " + bridge.watchers() + " editor(s) open.", NamedTextColor.AQUA));
            }
            default -> {
                sender.sendMessage(Component.text("/logic info", NamedTextColor.YELLOW).append(Component.text("  rules of the Logic Block you're looking at", NamedTextColor.GRAY)));
                if (sender.hasPermission("logicblocks.admin")) {
                    sender.sendMessage(Component.text("/logic give [player] [amount]", NamedTextColor.YELLOW));
                    sender.sendMessage(Component.text("/logic status", NamedTextColor.YELLOW));
                    sender.sendMessage(Component.text("/logic demo sorter [item ...]", NamedTextColor.YELLOW)
                        .append(Component.text("  build a working item sorter next to you", NamedTextColor.GRAY)));
                }
            }
        }
        return true;
    }

    /** /logic demo sorter [item ...]: builds docs/SORTER.md's sorter beside the player, programmed and stocked. */
    private void demo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("logicblocks.admin")) { sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED)); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        if (args.length < 2 || !args[1].equalsIgnoreCase("sorter")) {
            p.sendMessage(Component.text("/logic demo sorter [item ...]", NamedTextColor.YELLOW)
                .append(Component.text("  e.g. /logic demo sorter diamond emerald diamond_sword", NamedTextColor.GRAY)));
            return;
        }
        List<Material> sorted = new java.util.ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Material m = Material.matchMaterial(args[i]);
            if (m == null || !m.isItem() || m.isAir()) { p.sendMessage(Component.text("Not an item: " + args[i], NamedTextColor.RED)); return; }
            if (!sorted.contains(m)) sorted.add(m);
        }
        if (sorted.isEmpty()) sorted.addAll(Demos.DEFAULT_SORTED);
        if (sorted.size() > Demos.MAX_STATIONS) { p.sendMessage(Component.text("At most " + Demos.MAX_STATIONS + " items.", NamedTextColor.RED)); return; }
        // Two blocks east and three south of the player, at their feet: the line runs east, Logic Blocks on its north side.
        Block feet = p.getLocation().getBlock();
        int x = feet.getX() + 2, y = feet.getY(), z = feet.getZ() + 3;
        Block blocked = Demos.sorterBlocked(p.getWorld(), x, y, z, sorted.size());
        if (blocked != null) {
            p.sendMessage(Component.text("Not enough room: " + blocked.getType().getKey().getKey() + " at " + blocked.getX() + " " + blocked.getY() + " " + blocked.getZ()
                + ". It needs a clear space " + (sorted.size() + 2) + " long, 2 wide and 5 high, starting 1 east and 2 south of you.", NamedTextColor.RED));
            return;
        }
        Demos.Sorter built = Demos.sorter(manager, p.getWorld(), x, y, z, sorted, p.getUniqueId(), p.getName());
        Demos.fillSample(Demos.inventory(built.feeder()), sorted);
        p.sendMessage(Component.text("Built a sorter for " + sorted.stream().map(m -> m.getKey().getKey()).toList()
            + ". The line runs east; the feeder chest (top, west end) is already stocked with a mix. Each item lands in the chest under its station, the rest in the overflow chest at the east end. How it works: docs/SORTER.md", NamedTextColor.GREEN));
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 1; } }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return sender.hasPermission("logicblocks.admin") ? List.of("info", "give", "status", "demo") : List.of("info");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return null; // player names
        if (args.length == 2 && args[0].equalsIgnoreCase("demo")) return List.of("sorter");
        if (args.length >= 3 && args[0].equalsIgnoreCase("demo")) {
            String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
            return java.util.Arrays.stream(Material.values()).filter(m -> m.isItem() && !m.isAir() && !m.isLegacy())
                .map(m -> m.getKey().getKey()).filter(k -> k.startsWith(prefix)).limit(40).toList();
        }
        return List.of();
    }
}
