package dev.whizzle.logic.client.ui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/** {@link Painter} onto the game's screen. */
final class GuiPainter implements Painter {
	private static final Map<String, ItemStack> ICONS = new HashMap<>();
	private final GuiGraphicsExtractor g;
	private final Font font;

	GuiPainter(GuiGraphicsExtractor g, Font font) {
		this.g = g;
		this.font = font;
	}

	@Override public void fill(int x1, int y1, int x2, int y2, int argb) { g.fill(x1, y1, x2, y2, argb); }
	@Override public void text(String s, int x, int y, int argb) { g.text(font, s, x, y, argb); }
	@Override public void plain(String s, int x, int y, int argb) { g.text(font, s, x, y, argb, false); }
	@Override public int width(String s) { return font.width(s); }
	@Override public String fit(String s, int w) { return font.plainSubstrByWidth(s, Math.max(0, w)); }

	@Override public void icon(String id, boolean block, int x, int y) {
		ItemStack stack = ICONS.computeIfAbsent((block ? "b|" : "i|") + id, k -> stack(id, block));
		if (!stack.isEmpty()) g.item(stack, x, y);
	}

	/** A block is shown by its item; one without an item (fire, water) has no icon. */
	private static ItemStack stack(String id, boolean block) {
		Identifier key = Identifier.tryParse(id);
		if (key == null) return ItemStack.EMPTY;
		if (block) return BuiltInRegistries.BLOCK.getOptional(key).map(b -> new ItemStack(b.asItem())).orElse(ItemStack.EMPTY);
		return BuiltInRegistries.ITEM.getOptional(key).map(i -> new ItemStack(i)).orElse(ItemStack.EMPTY);
	}
}
