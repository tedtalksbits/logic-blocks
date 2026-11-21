package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.client.ui.EditorLayout.El;
import dev.whizzle.logic.client.ui.EditorLayout.Lamp;

import java.util.List;
import java.util.function.Function;

/**
 * Draws what the editor draws itself, over the game's own buttons and boxes: words, lamps, the label and arrow of a
 * button that opens a list, the icons under a name, a card's two lines, and the small panel for hovering a lamp.
 * Through {@link Painter}, so it can be drawn to a picture in tests.
 */
public final class EditorPaint {
	private EditorPaint() {}

	static final int LAMP_ON = 0xFF6EE07A, LAMP_ON_CORE = 0xFFC8FFD0, LAMP_OFF = 0xFF7A2A2A, LAMP_OFF_CORE = 0xFF9A3A3A, LAMP_NONE = 0xFF5A5F6A,
		INACTIVE = 0xFFA0A0A0;

	/**
	 * @param left  the x of the editor's column on the screen (the elements' own x is within the column)
	 * @param strip the icons to show under a name element, worked out by the screen from the box's text
	 */
	public static void draw(Painter p, List<El> els, int left, Function<El, NamePicker.Strip> strip) {
		for (El el : els) {
			int x = left + el.x, y = el.y;
			switch (el.kind) {
				case WORD -> {
					String text = el.live != null ? el.live.get() : el.text;
					if (text == null || text.isEmpty()) continue;
					p.text(el.live != null ? p.fit(text, el.w) : text, x, y, el.liveColor != null ? el.liveColor.getAsInt() : el.color);
				}
				case LAMP -> lamp(p, x, y, el.lamp.get().state());
				case FRAME -> {
					p.fill(x, y, x + el.w, y + 1, el.color);
					p.fill(x, y + el.h - 1, x + el.w, y + el.h, el.color);
					p.fill(x, y, x + 1, y + el.h, el.color);
					p.fill(x + el.w - 1, y, x + el.w, y + el.h, el.color);
				}
				case CHOICE -> {
					int color = el.active ? 0xFFFFFFFF : INACTIVE, tx = x + 5;
					String icon = el.icon == null ? null : el.icon.get();
					if (el.icon != null) { if (icon != null && !icon.isEmpty()) p.icon(icon, true, x + 2, y + 1); tx = x + 21; }
					p.text(el.text, tx, y + 5, color);
					// A small arrow: this opens a list.
					int ax = x + el.w - 11, ay = y + 8;
					p.fill(ax, ay, ax + 5, ay + 1, color);
					p.fill(ax + 1, ay + 1, ax + 4, ay + 2, color);
					p.fill(ax + 2, ay + 2, ax + 3, ay + 3, color);
				}
				case MENU -> {
					for (int i = 0; i < 3; i++) p.fill(x + 5, y + 5 + i * 3, x + el.w - 5, y + 6 + i * 3, 0xFFFFFFFF);
				}
				case CARD -> {
					p.text(p.fit(el.text, el.w - 10), x + 5, y + 6, 0xFFFFFFFF);
					p.text(p.fit(el.sub, el.w - 10), x + 5, y + 18, EditorLayout.GRAY);
				}
				case STRIP -> {
					NamePicker.Strip s = strip.apply(el);
					if (s == null) continue;
					// As many icons as there's room for, leaving space for the count of the rest.
					int shown = Math.min(s.icons().size(), Math.max(1, (el.w - 26) / 17)), more = s.more() + s.icons().size() - shown, ix = x;
					for (String id : s.icons().subList(0, shown)) { p.icon(id, el.block, ix, y); ix += 17; }
					if (more > 0) p.text("+" + more, ix + 1, y + 4, EditorLayout.GRAY);
					if (s.problem() != null) p.text(p.fit(s.problem(), el.w), x, y + 4, EditorLayout.RED);
				}
				default -> { }
			}
		}
	}

	/** An 8 by 8 lamp. {@code state}: 0 dark red, 1 lit green, 2 grey. */
	public static void lamp(Painter p, int x, int y, int state) {
		p.fill(x, y, x + 8, y + 8, 0xFF000000);
		p.fill(x + 1, y + 1, x + 7, y + 7, state == 1 ? LAMP_ON : state == 0 ? LAMP_OFF : LAMP_NONE);
		if (state != 2) p.fill(x + 2, y + 2, x + 4, y + 4, state == 1 ? LAMP_ON_CORE : LAMP_OFF_CORE);
	}

	/** The lamp under the pointer (or within a few pixels of it: they're small), or null. */
	public static El lampAt(List<El> els, int left, double mouseX, double mouseY) {
		double x = mouseX - left;
		for (El el : els) if (el.kind == EditorLayout.Kind.LAMP && x >= el.x - 3 && x < el.x + el.w + 3 && mouseY >= el.y - 3 && mouseY < el.y + el.h + 3) return el;
		return null;
	}

	/** A small panel by the pointer with what a lamp's condition sees right now and whether that makes it true. */
	public static void tip(Painter p, Lamp lamp, int mouseX, int mouseY, int screenW, int screenH) {
		String verdict = lamp.state() == 1 ? "true" : lamp.state() == 0 ? "not true" : "";
		List<String> lines = EditorLayout.wrap(p, lamp.reading(), Math.min(240, screenW - 20));
		int inner = p.width(verdict);
		for (String line : lines) inner = Math.max(inner, p.width(line));
		int w = inner + 10, h = 8 + lines.size() * 10 + (verdict.isEmpty() ? 0 : 10);
		int[] at = NamePanel.byPointer(mouseX, mouseY, w, h, screenW, screenH);
		NamePanel.frame(p, at[0], at[1], w, h, 0xF0100010, 0xFF5000A0);
		int y = at[1] + 4;
		for (String line : lines) { p.text(line, at[0] + 5, y, 0xFFFFFFFF); y += 10; }
		if (!verdict.isEmpty()) p.text(verdict, at[0] + 5, y, lamp.state() == 1 ? EditorLayout.GREEN : EditorLayout.RED);
	}
}
