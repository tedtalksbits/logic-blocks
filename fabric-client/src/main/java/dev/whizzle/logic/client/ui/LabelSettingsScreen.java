package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.client.Settings;
import dev.whizzle.logic.client.labels.LabelStyle;
import dev.whizzle.logic.client.labels.SideLabels;
import dev.whizzle.logic.model.Side;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;

/**
 * Each player's side-label settings. Left: the controls. Right: a preview of a Logic Block with its labels from above
 * (north up) and from the south, drawn with the daylight detector's own textures, so the effect of each slider is
 * visible without the world behind the menu. Changes apply at once (also to the labels in the world) and are saved
 * when the screen closes.
 */
public final class LabelSettingsScreen extends Screen {
	private static final Identifier TOP = Identifier.withDefaultNamespace("textures/block/daylight_detector_top.png");
	private static final Identifier SIDE = Identifier.withDefaultNamespace("textures/block/daylight_detector_side.png");
	private static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFB8C0CC, AQUA = 0xFF6FD3FF, PANEL = 0x90101418, FRAME = 0xFF3A4250, FRAME_TEXT = 0xFF6A7487;

	private final @Nullable Screen parent;
	private final KeyMapping toggleKey;
	private int controlsW, previewX, hintY;

	public LabelSettingsScreen(@Nullable Screen parent, KeyMapping toggleKey) {
		super(Component.literal("Logic Blocks: side labels"));
		this.parent = parent;
		this.toggleKey = toggleKey;
	}

	@Override
	protected void init() {
		controlsW = Math.clamp(width / 2 - 30, 150, 200);
		previewX = 20 + controlsW + 16;
		int x = 20, y = 30, step = 24;
		LabelStyle s = Settings.labelStyle;

		addRenderableWidget(Button.builder(onOffText(), b -> {
			Settings.sideLabels = !Settings.sideLabels;
			b.setMessage(onOffText());
		}).pos(x, y).size(controlsW, 20).build());
		y += step + 4;
		addRenderableWidget(new Slider(x, y, controlsW, "Size", LabelStyle.MIN_SIZE, LabelStyle.MAX_SIZE, 0.05, s.size(),
			v -> Math.round(v * 100) + "%", v -> Settings.labelStyle = Settings.labelStyle.withSize(v)));
		y += step;
		addRenderableWidget(new Slider(x, y, controlsW, "Height", LabelStyle.MIN_HEIGHT, LabelStyle.MAX_HEIGHT, 0.01, s.height(),
			v -> String.format(Locale.ROOT, "%.2f blocks", v), v -> Settings.labelStyle = Settings.labelStyle.withHeight(v)));
		y += step;
		addRenderableWidget(new Slider(x, y, controlsW, "From centre", LabelStyle.MIN_OFFSET, LabelStyle.MAX_OFFSET, 0.01, s.offset(),
			v -> String.format(Locale.ROOT, "%.2f blocks", v), v -> Settings.labelStyle = Settings.labelStyle.withOffset(v)));
		y += step;
		addRenderableWidget(new Slider(x, y, controlsW, "Background", 0, 100, 5, s.background(),
			v -> Math.round(v) + "%", v -> Settings.labelStyle = Settings.labelStyle.withBackground((int) Math.round(v))));
		y += step;
		addRenderableWidget(Button.builder(colorText("North", s.northColor()), b -> {
			Settings.labelStyle = Settings.labelStyle.withNorthColor(LabelStyle.nextColor(Settings.labelStyle.northColor()));
			b.setMessage(colorText("North", Settings.labelStyle.northColor()));
		}).pos(x, y).size(controlsW, 20).build());
		y += step;
		addRenderableWidget(Button.builder(colorText("E, S, W", s.otherColor()), b -> {
			Settings.labelStyle = Settings.labelStyle.withOtherColor(LabelStyle.nextColor(Settings.labelStyle.otherColor()));
			b.setMessage(colorText("E, S, W", Settings.labelStyle.otherColor()));
		}).pos(x, y).size(controlsW, 20).build());

		int by = height - 28, half = (controlsW - 4) / 2;
		hintY = y + 26 + 9 <= by - 2 ? y + 26 : -1;
		addRenderableWidget(Button.builder(Component.literal("Reset"), b -> {
			Settings.labelStyle = LabelStyle.DEFAULT;
			rebuildWidgets();
		}).pos(x, by).size(half, 20).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).pos(x + half + 4, by).size(half, 20).build());
	}

	/** "Labels: On  (key N)": the key that toggles them in game, as the player has bound it. */
	private Component onOffText() {
		String key = toggleKey.isUnbound() ? "" : "  (key " + toggleKey.getTranslatedKeyMessage().getString() + ")";
		return Component.literal("Labels: " + (Settings.sideLabels ? "On" : "Off") + key);
	}

	private static Component colorText(String which, int rgb) {
		return Component.literal(which + ": ").append(Component.literal(LabelStyle.colorName(rgb)).withColor(rgb));
	}

	@Override
	public void onClose() {
		Settings.save();
		minecraft.gui.setScreen(parent);
	}

	@Override
	public boolean isPauseScreen() { return false; }

	// ---- drawing ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.centeredText(font, title, width / 2, 10, AQUA);
		if (hintY >= 0) {
			g.text(font, font.plainSubstrByWidth("Shown on the Logic Block you look at.", controlsW), 20, hintY, GRAY);
		}
		preview(g);
	}

	/** Top view above, side view below, scaled to the space right of the controls. */
	private void preview(GuiGraphicsExtractor g) {
		int left = previewX, right = width - 16, top = 28, bottom = height - 12;
		int w = right - left, h = bottom - top;
		if (w < 60 || h < 80) return;
		g.fill(left, top, right, bottom, PANEL);
		g.outline(left, top, w, h, FRAME);
		LabelStyle style = Settings.labelStyle;

		// Each view gets a caption line; the side view needs room for the tallest labels above the block.
		int caption = 12;
		double sideRoom = LabelStyle.MAX_HEIGHT + 0.35;
		int block = (int) Math.min(w * 0.55, (h - 3 * caption - 16) / (1.2 + sideRoom));
		block = Math.max(16, block - block % 16);
		int bx = left + (w - block) / 2;

		int ty = top + caption + 6;
		g.text(font, "From above (north up)", left + 6, top + 5, GRAY);
		g.blit(TOP, bx, ty, bx + block, ty + block, 0, 1, 0, 1);
		for (SideLabels.Glyph glyph : SideLabels.topView(style, bx, ty, block)) glyph(g, glyph, false);

		int sy = ty + block + caption + 8;
		g.text(font, "From the south", left + 6, sy - caption + 2, GRAY);
		int base = sy + (int) Math.round(sideRoom * block) + (int) Math.round(SideLabels.BLOCK_HEIGHT * block);
		int bandH = (int) Math.round(SideLabels.BLOCK_HEIGHT * block);
		// The side of a daylight detector is the bottom 6 rows of its side texture.
		g.blit(SIDE, bx, base - bandH, bx + block, base, 0, 1, 10 / 16F, 1);
		g.fill(left + 4, base, right - 4, base + 1, FRAME);
		// Where a full block placed on top of the Logic Block would end: labels above it stay in the open.
		int oneBlock = base - block;
		for (int dx = left + 4; dx < right - 4; dx += 4) g.fill(dx, oneBlock, dx + 2, oneBlock + 1, FRAME);
		g.text(font, "1 block", right - 6 - font.width("1 block"), oneBlock - 10, FRAME_TEXT);
		for (SideLabels.Glyph glyph : SideLabels.sideView(style, bx, base, block)) glyph(g, glyph, glyph.side() == Side.NORTH);
	}

	/** One letter centred on the glyph's point, with the chosen backing; {@code behind} letters are dimmed. */
	private void glyph(GuiGraphicsExtractor g, SideLabels.Glyph glyph, boolean behind) {
		int tw = font.width(glyph.text());
		g.pose().pushMatrix();
		g.pose().translate((float) glyph.x(), (float) glyph.y());
		g.pose().scale((float) glyph.scale(), (float) glyph.scale());
		int bg = Settings.labelStyle.backgroundArgb();
		if (bg != 0) g.fill(-tw / 2 - 1, -5, tw - tw / 2, 4, bg);
		int color = behind ? (glyph.color() & 0x00FFFFFF) | 0x80000000 : glyph.color();
		g.text(font, glyph.text(), -tw / 2, -4, color, false);
		g.pose().popMatrix();
	}

	/** A slider over [min, max] in steps, showing "label: value". */
	private static final class Slider extends AbstractSliderButton {
		private final String label;
		private final double min, max, step;
		private final DoubleFunction<String> format;
		private final DoubleConsumer apply;

		Slider(int x, int y, int w, String label, double min, double max, double step, double current, DoubleFunction<String> format, DoubleConsumer apply) {
			super(x, y, w, 20, Component.empty(), (current - min) / (max - min));
			this.label = label;
			this.min = min;
			this.max = max;
			this.step = step;
			this.format = format;
			this.apply = apply;
			updateMessage();
		}

		private double current() { return Math.clamp(Math.round((min + value * (max - min)) / step) * step, min, max); }

		@Override protected void updateMessage() { setMessage(Component.literal(label + ": " + format.apply(current()))); }
		@Override protected void applyValue() { apply.accept(current()); }
	}
}
