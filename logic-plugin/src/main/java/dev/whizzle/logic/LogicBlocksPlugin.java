package dev.whizzle.logic;

import dev.whizzle.logic.model.Program;
import io.papermc.paper.event.server.ServerResourcesReloadedEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
        getServer().getPluginManager().registerEvents(new PickBlock(manager), this);
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
                    sender.sendMessage(Component.text("/logic demo sorter|redstone-sorter [item ...]", NamedTextColor.YELLOW)
                        .append(Component.text("  build a working item sorter next to you", NamedTextColor.GRAY)));
                    sender.sendMessage(Component.text("/logic demo lock", NamedTextColor.YELLOW)
                        .append(Component.text("  build a combination lock (counters) next to you", NamedTextColor.GRAY)));
                }
            }
        }
        return true;
    }

    /**
     * /logic demo sorter [item ...]: the v2 sorter (one Logic Block per station, moving items);
     * /logic demo redstone-sorter [item ...]: the pure-redstone sorter (two per station). Both from docs/SORTER.md,
     * built beside the player, programmed, owned by them, with a stocked feeder chest.
     */
    /** /logic demo <machine> [n]: one of {@link Machines}, starting 2 blocks east and 2 south of the player, stocked. */
    private void demoMachine(Player p, Machines.Kind kind, String[] args) {
        int n = switch (kind) { case ROCKET_CRAFTER -> 3; case SUPER_SMELTER -> Machines.MAX_FURNACES; default -> 0; };
        int max = kind == Machines.Kind.ROCKET_CRAFTER ? 3 : Machines.MAX_FURNACES;
        if (args.length >= 3 && n > 0) {
            try { n = Integer.parseInt(args[2]); } catch (NumberFormatException e) { n = -1; }
            if (n < 1 || n > max) {
                p.sendMessage(Component.text((kind == Machines.Kind.ROCKET_CRAFTER ? "Flight duration" : "Furnaces") + ": 1 to " + max + ".", NamedTextColor.RED));
                return;
            }
        }
        Block feet = p.getLocation().getBlock();
        int x = feet.getX() + 2, y = feet.getY(), z = feet.getZ() + 2;
        Block blocked = Machines.blocked(p.getWorld(), x, y, z, kind, n);
        if (blocked != null) {
            int[] s = Machines.size(kind, n);
            p.sendMessage(Component.text("Not enough room: " + blocked.getType().getKey().getKey() + " at " + blocked.getX() + " " + blocked.getY() + " " + blocked.getZ()
                + ". It needs a clear space " + s[0] + " wide (east), " + s[2] + " deep (south) and " + s[1] + " high, starting 2 east and 2 south of you.", NamedTextColor.RED));
            return;
        }
        Machines.build(manager, p.getWorld(), x, y, z, kind, n, p.getUniqueId(), p.getName());
        String how = switch (kind) {
            case IRON_CRAFTER -> "Iron ingots go in the chest on top; iron blocks come out in the chest in front of the crafter.";
            case ROCKET_CRAFTER -> "Paper and gunpowder go in the chest on top; flight " + n + " rockets come out in the chest in front of the crafter.";
            case SUPER_SMELTER -> n + " furnaces. Things to smelt go in the top chest, coal in the chest at the back; results come out in the chest at the bottom east end.";
            case SHULKER_LOADER -> "Empty shulker boxes go in the top chest (over the dispenser), items in the chest at the east end; full boxes come out in the chest at the bottom west.";
            case SHULKER_UNLOADER -> "Full shulker boxes go in the top chest (over the dispenser); their items and the empty boxes come out in the chest at the bottom west.";
        };
        p.sendMessage(Component.text("Built a " + kind.command.replace('-', ' ') + " next to you, stocked so it starts working. " + how + " How it works: docs/BUILDS.md", NamedTextColor.GREEN));
    }

    /** /logic demo lock: the combination lock (Demos.lock), 3 blocks south of the player, door facing them. */
    private void demoLock(Player p) {
        Block feet = p.getLocation().getBlock();
        int x = feet.getX(), y = feet.getY(), z = feet.getZ() - 4;
        Block blocked = Demos.lockBlocked(p.getWorld(), x, y, z);
        if (blocked != null) {
            p.sendMessage(Component.text("Not enough room: " + blocked.getType().getKey().getKey() + " at " + blocked.getX() + " " + blocked.getY() + " " + blocked.getZ()
                + ". It needs a clear space 5 wide, 4 deep and 2 high, starting 3 blocks north of you.", NamedTextColor.RED));
            return;
        }
        Demos.lock(manager, p.getWorld(), x, y, z, p.getUniqueId(), p.getName());
        p.sendMessage(Component.text("Built a combination lock north of you. Press the buttons in the order on the signs (1, 2, 3) and the door opens"
            + " for 3 seconds; a press out of order starts over. Its counter A remembers how far you are: right-click the Logic Block to watch it.", NamedTextColor.GREEN));
    }

    private void demo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("logicblocks.admin")) { sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED)); return; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return; }
        String which = args.length < 2 ? "" : args[1].toLowerCase(java.util.Locale.ROOT);
        // Demos place blocks and stock chests: free resources, so creative mode only, even for operators.
        if (!which.isEmpty() && p.getGameMode() != GameMode.CREATIVE) {
            p.sendMessage(Component.text("Demos build and stock machines for free, so they only work in creative mode.", NamedTextColor.RED));
            return;
        }
        if (which.equals("lock")) { demoLock(p); return; }
        Machines.Kind machine = Machines.Kind.of(which);
        if (machine != null) { demoMachine(p, machine, args); return; }
        if (!which.equals("sorter") && !which.equals("redstone-sorter")) {
            p.sendMessage(Component.text("/logic demo sorter [item ...]", NamedTextColor.YELLOW)
                .append(Component.text("  one Logic Block per station, moving items", NamedTextColor.GRAY)));
            p.sendMessage(Component.text("/logic demo redstone-sorter [item ...]", NamedTextColor.YELLOW)
                .append(Component.text("  pure redstone: two per station, locking hoppers", NamedTextColor.GRAY)));
            p.sendMessage(Component.text("/logic demo lock", NamedTextColor.YELLOW)
                .append(Component.text("  a combination lock: three buttons in the right order open a door", NamedTextColor.GRAY)));
            p.sendMessage(Component.text("/logic demo iron-crafter | rocket-crafter [1-3] | super-smelter [1-8] | shulker-loader | shulker-unloader", NamedTextColor.YELLOW)
                .append(Component.text("  machines built from vanilla blocks (docs/BUILDS.md)", NamedTextColor.GRAY)));
            return;
        }
        boolean moving = which.equals("sorter");
        List<Material> sorted = new java.util.ArrayList<>();
        for (int i = 2; i < args.length; i++) {
            Material m = Material.matchMaterial(args[i]);
            if (m == null || !m.isItem() || m.isAir()) { p.sendMessage(Component.text("Not an item: " + args[i], NamedTextColor.RED)); return; }
            if (!sorted.contains(m)) sorted.add(m);
        }
        if (sorted.isEmpty()) sorted.addAll(Demos.DEFAULT_SORTED);
        if (sorted.size() > Demos.MAX_STATIONS) { p.sendMessage(Component.text("At most " + Demos.MAX_STATIONS + " items.", NamedTextColor.RED)); return; }
        // Two blocks east and three south of the player, at their feet; the line runs east.
        Block feet = p.getLocation().getBlock();
        int x = feet.getX() + 2, y = feet.getY(), z = feet.getZ() + 3;
        Block blocked = moving ? Demos.moveSorterBlocked(p.getWorld(), x, y, z, sorted.size()) : Demos.sorterBlocked(p.getWorld(), x, y, z, sorted.size());
        if (blocked != null) {
            p.sendMessage(Component.text("Not enough room: " + blocked.getType().getKey().getKey() + " at " + blocked.getX() + " " + blocked.getY() + " " + blocked.getZ()
                + ". It needs a clear space " + (sorted.size() + 2) + " long, " + (moving ? "1 wide and 4" : "2 wide and 5")
                + " high, starting 1 east and " + (moving ? "3" : "2") + " south of you.", NamedTextColor.RED));
            return;
        }
        Demos.Sorter built = moving
            ? Demos.moveSorter(manager, p.getWorld(), x, y, z, sorted, p.getUniqueId(), p.getName())
            : Demos.sorter(manager, p.getWorld(), x, y, z, sorted, p.getUniqueId(), p.getName());
        Demos.fillSample(Demos.inventory(built.feeder()), sorted);
        p.sendMessage(Component.text("Built a" + (moving ? "" : " redstone") + " sorter for " + sorted.stream().map(m -> m.getKey().getKey()).toList()
            + ". The line runs east; the feeder chest (top, west end) is stocked with a mix. Each item lands in the chest under its station, the rest in the overflow chest at the east end. How it works: docs/SORTER.md", NamedTextColor.GREEN));
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 1; } }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return sender.hasPermission("logicblocks.admin") ? List.of("info", "give", "status", "demo") : List.of("info");
        if (args.length == 2 && args[0].equalsIgnoreCase("give")) return null; // player names
        if (args.length == 2 && args[0].equalsIgnoreCase("demo")) {
            List<String> all = new java.util.ArrayList<>(List.of("sorter", "redstone-sorter", "lock"));
            for (Machines.Kind k : Machines.Kind.values()) all.add(k.command);
            return all;
        }
        if (args.length >= 3 && args[0].equalsIgnoreCase("demo") && args[1].toLowerCase(java.util.Locale.ROOT).endsWith("sorter")) {
            String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
            return java.util.Arrays.stream(Material.values()).filter(m -> m.isItem() && !m.isAir() && !m.isLegacy())
                .map(m -> m.getKey().getKey()).filter(k -> k.startsWith(prefix)).limit(40).toList();
        }
        return List.of();
    }
}
