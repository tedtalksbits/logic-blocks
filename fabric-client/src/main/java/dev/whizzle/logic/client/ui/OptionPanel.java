package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.client.ui.EditorLayout.Option;

import java.util.List;

/**
 * The list a button opens: every option with a few words on what it is, the current one marked. Seeing them all at
 * once is how a new player learns what there is (the old buttons stepped through them one click at a time).
 */
public final class OptionPanel {
	private OptionPanel() {}

	static final int ROW = 14, PAD = 3, MARK = 8;

	public record Layout(int x, int y, int w, int h, int rows, int noteX) {
		public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }

		/** The option under the pointer, or -1. */
		public int indexAt(double mx, double my) {
			if (!contains(mx, my) || my < y + PAD) return -1;
			int i = (int) ((my - y - PAD) / ROW);
			return i < rows ? i : -1;
		}
	}

	/** Places the list under the button at ax, ay (above it when there's no room below), kept inside the screen. */
	public static Layout layout(Painter p, List<Option> options, int ax, int ay, int ah, int screenW, int screenH) {
		int label = 0, note = 0;
		for (Option o : options) { label = Math.max(label, p.width(o.label())); note = Math.max(note, p.width(o.note())); }
		int w = Math.min(PAD + MARK + label + (note > 0 ? 10 + note : 0) + PAD + 3, screenW - 8), h = PAD + options.size() * ROW + PAD;
		int x = Math.max(4, Math.min(ax, screenW - w - 4));
		int y = ay + ah + 1;
		if (y + h > screenH - 2) y = ay - h - 1 >= 2 ? ay - h - 1 : Math.max(2, screenH - h - 2);
		return new Layout(x, y, w, h, options.size(), x + PAD + MARK + label + 10);
	}

	/** {@code current}: the option in use (marked); {@code highlight}: the one under the pointer or chosen with the keys. */
	public static void draw(Painter p, List<Option> options, Layout l, int current, int highlight) {
		NamePanel.frame(p, l.x(), l.y(), l.w(), l.h(), NamePanel.BACK, NamePanel.EDGE);
		int y = l.y() + PAD;
		for (int i = 0; i < options.size(); i++) {
			Option o = options.get(i);
			if (i == highlight && o.enabled()) p.fill(l.x() + 1, y, l.x() + l.w() - 1, y + ROW, NamePanel.HIGHLIGHT);
			if (i == current) p.fill(l.x() + PAD + 1, y + 4, l.x() + PAD + 4, y + 10, EditorLayout.AQUA);
			p.text(o.label(), l.x() + PAD + MARK, y + 3, !o.enabled() ? 0xFF6A6F7A : i == current ? EditorLayout.AQUA : 0xFFFFFFFF);
			if (!o.note().isEmpty()) p.text(p.fit(o.note(), l.x() + l.w() - PAD - l.noteX()), l.noteX(), y + 3, o.enabled() ? NamePanel.DIM : 0xFF5A5F6A);
			y += ROW;
		}
	}
}
