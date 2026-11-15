package dev.whizzle.logic.model;

import java.util.ArrayList;
import java.util.List;

/**
 * The Logic Block item's name and lore text, shared by the plugin ({@code LogicItem}) and the mod's creative tab, so
 * the two build the same item. Styling (aqua name, grey lore, not italic) is set on each side; the self-test checks
 * that the mod's item and the plugin's stack together ("creative tab").
 */
public final class ItemText {
    private ItemText() {}

    public static final String NAME = "Logic Block";

    /** The lore lines for an item carrying {@code program} (null or empty: a blank Logic Block). */
    public static List<String> lore(Program program) {
        List<String> lines = new ArrayList<>();
        if (program == null || program.equals(Program.EMPTY)) {
            lines.add("Place it, then right-click to program it.");
        } else {
            lines.add("Programmed:");
            for (String line : program.describe()) lines.add("  " + line);
        }
        return lines;
    }
}
