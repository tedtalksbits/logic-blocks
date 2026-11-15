package dev.whizzle.logic;

import dev.whizzle.logic.model.ItemText;
import dev.whizzle.logic.model.Program;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;

/** The Logic Block item (a daylight detector with a marker), optionally carrying a program, and its crafting recipe. */
public final class LogicItem {
    public static final NamespacedKey ITEM_KEY = new NamespacedKey("logicblocks", "logic_block");
    public static final NamespacedKey PROGRAM_KEY = new NamespacedKey("logicblocks", "program");
    public static final NamespacedKey RECIPE_KEY = new NamespacedKey("logicblocks", "logic_block");
    /** The block a Logic Block is: powers all sides at 0-15 and doesn't conduct incoming power through itself. */
    public static final Material BLOCK = Material.DAYLIGHT_DETECTOR;

    private LogicItem() {}

    public static ItemStack create(Program program) {
        ItemStack item = new ItemStack(BLOCK);
        ItemMeta meta = item.getItemMeta();
        // The mod's creative tab builds the same item (fabric-client CreativeTab): change both, and the self-test's
        // "creative tab" step, together.
        meta.displayName(Component.text(ItemText.NAME, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : ItemText.lore(program)) lore.add(gray(line));
        if (program != null && !program.equals(Program.EMPTY))
            meta.getPersistentDataContainer().set(PROGRAM_KEY, PersistentDataType.BYTE_ARRAY, program.encode());
        meta.lore(lore);
        meta.getPersistentDataContainer().set(ITEM_KEY, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        setModel(item);
        return item;
    }

    /** The custom model data string the mod's item definition picks the Logic Block look by. */
    public static final String MODEL = "logicblocks:logic_block";

    private static void setModel(ItemStack item) {
        item.setData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA,
            io.papermc.paper.datacomponent.item.CustomModelData.customModelData().addString(MODEL).build());
    }

    /**
     * Gives a Logic Block item made before plugin 0.5.0 its model string (so the mod draws it, and it stacks with new
     * ones). Returns whether it changed anything.
     */
    public static boolean upgrade(ItemStack item) {
        if (!is(item)) return false;
        var cmd = item.getData(io.papermc.paper.datacomponent.DataComponentTypes.CUSTOM_MODEL_DATA);
        if (cmd != null && cmd.strings().contains(MODEL)) return false;
        setModel(item);
        return true;
    }

    /** Upgrades every Logic Block item in {@code inv} ({@link #upgrade}). */
    public static void upgrade(org.bukkit.inventory.Inventory inv) {
        ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (ItemStack it : contents) changed |= upgrade(it);
        if (changed) inv.setContents(contents);
    }

    private static Component gray(String text) { return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false); }

    public static boolean is(ItemStack item) {
        return item != null && item.getType() == BLOCK && item.hasItemMeta()
            && item.getItemMeta().getPersistentDataContainer().has(ITEM_KEY, PersistentDataType.BYTE);
    }

    /** The program stored on the item, or an empty program (also for a damaged one). */
    public static Program program(ItemStack item) {
        byte[] data = item.getItemMeta().getPersistentDataContainer().get(PROGRAM_KEY, PersistentDataType.BYTE_ARRAY);
        if (data == null) return Program.EMPTY;
        try {
            Program p = Program.decode(data);
            return p.problem() == null ? p : Program.EMPTY;
        } catch (IllegalArgumentException e) { return Program.EMPTY; }
    }

    /** Registers the configurable shaped recipe; a bad recipe is logged and skipped (operators can use /logic give). */
    public static void registerRecipe(JavaPlugin plugin) {
        Bukkit.removeRecipe(RECIPE_KEY);
        try {
            ConfigurationSection section = plugin.getConfig().getConfigurationSection("recipe");
            if (section == null) throw new IllegalArgumentException("recipe is missing");
            List<String> shape = section.getStringList("shape");
            ConfigurationSection ingredients = section.getConfigurationSection("ingredients");
            if (shape.isEmpty() || shape.size() > 3 || ingredients == null) throw new IllegalArgumentException("recipe needs 1-3 shape rows and an ingredients map");
            ShapedRecipe recipe = new ShapedRecipe(RECIPE_KEY, create(null));
            recipe.shape(shape.toArray(String[]::new));
            for (String symbol : ingredients.getKeys(false)) {
                if (symbol.length() != 1) throw new IllegalArgumentException("ingredient keys must be single characters: " + symbol);
                String id = Objects.requireNonNullElse(ingredients.getString(symbol), "");
                Material material = Material.matchMaterial(id);
                if (material == null || !material.isItem() || material.isAir()) throw new IllegalArgumentException("unknown item: " + id);
                recipe.setIngredient(symbol.charAt(0), material);
            }
            if (!Bukkit.addRecipe(recipe)) throw new IllegalArgumentException("server rejected the recipe");
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Logic Block recipe not registered (" + e.getMessage() + "). Fix recipe in config.yml; operators can use /logic give meanwhile.");
        }
    }
}
