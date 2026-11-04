package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.model.Presets;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * Ready-made rules to start from (the same programs the plugin's demos build): a list on the left, the selected
 * preset's description and rules on the right, "Use" loads it into the editor (which still needs Save).
 */
public final class PresetScreen extends Screen {
	private final LogicScreen parent;
	private final List<Presets.Preset> presets = Presets.all();
	private int selected, cw, left, scroll;
	private static final int LIST_W = 150, ROW = 20;

	public PresetScreen(LogicScreen parent) {
		super(Component.literal("Logic Block presets"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		cw = Math.min(width, LogicScreen.MAX_WIDTH);
		left = (width - cw) / 2;
		int rows = Math.max(1, (height - 60) / ROW);
		scroll = Math.clamp(scroll, 0, Math.max(0, presets.size() - rows));
		if (selected < scroll) scroll = selected;
		if (selected >= scroll + rows) scroll = selected - rows + 1;
		int y = 30;
		for (int i = scroll; i < presets.size() && i < scroll + rows; i++) {
			int index = i;
			String label = (i == selected ? "> " : "") + presets.get(i).name();
			addRenderableWidget(Button.builder(Component.literal(font.plainSubstrByWidth(label, LIST_W - 8)), b -> { selected = index; rebuildWidgets(); })
				.pos(6, y).size(LIST_W, 18).build());
			y += ROW;
		}
		if (scroll > 0) addRenderableWidget(Button.builder(Component.literal("^"), b -> { scroll--; rebuildWidgets(); }).pos(LIST_W + 8, 30).size(16, 18).build());
		if (scroll + rows < presets.size())
			addRenderableWidget(Button.builder(Component.literal("v"), b -> { scroll++; rebuildWidgets(); }).pos(LIST_W + 8, 30 + (rows - 1) * ROW).size(16, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Use"), b -> use()).pos(cw - 110, 4).size(50, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose()).pos(cw - 56, 4).size(50, 18).build());
		if (left > 0) for (var child : children()) if (child instanceof AbstractWidget w) w.setX(w.getX() + left);
	}

	private void use() {
		Presets.Preset p = presets.get(selected);
		minecraft.gui.setScreen(parent);
		parent.replace(p.program(), "the \"" + p.name() + "\" preset");
	}

	@Override
	public void onClose() { minecraft.gui.setScreen(parent); }

	@Override
	public boolean isPauseScreen() { return false; }

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.pose().pushMatrix();
		g.pose().translate(left, 0);
		g.text(font, "Presets", 6, 9, LogicScreen.AQUA);
		g.text(font, font.plainSubstrByWidth("Ready-made rules. Use loads them into the editor; nothing changes on the block until you Save.", cw - 180), 60, 9, LogicScreen.GRAY);
		Presets.Preset p = presets.get(selected);
		int x = LIST_W + 30, w = cw - x - 8, y = 30;
		g.text(font, p.name(), x, y, LogicScreen.WHITE);
		y += 14;
		for (var line : font.split(Component.literal(p.description()), Math.max(60, w))) { g.text(font, line, x, y, LogicScreen.GRAY); y += 11; }
		y += 8;
		for (String rule : p.program().describe())
			for (var line : font.split(Component.literal(rule), Math.max(60, w))) { g.text(font, line, x, y, LogicScreen.GREEN); y += 11; }
		g.pose().popMatrix();
	}
}
