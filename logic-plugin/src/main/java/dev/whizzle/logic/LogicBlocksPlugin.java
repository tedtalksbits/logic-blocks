package dev.whizzle.logic;

import dev.whizzle.logic.model.Program;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
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
            case "status" -> {
                if (!sender.hasPermission("logicblocks.admin")) { sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED)); return true; }
                sender.sendMessage(Component.text(manager.nodes().size() + " Logic Blocks loaded, " + bridge.watchers() + " editor(s) open.", NamedTextColor.AQUA));
            }
            default -> {
                sender.sendMessage(Component.text("/logic info", NamedTextColor.YELLOW).append(Component.text("  rules of the Logic Block you're looking at", NamedTextColor.GRAY)));
                if (sender.hasPermission("logicblocks.admin")) {
                    sender.sendMessage(Component.text("/logic give [player] [amount]", NamedTextColor.YELLOW));
                    sender.sendMessage(Component.text("/logic status", NamedTextColor.YELLOW));
                }
            }
        }
        return true;
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 1; } }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return sender.hasPermission("logicblocks.admin") ? List.of("info", "give", "status") : List.of("info");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return null; // player names
        return List.of();
    }
}
