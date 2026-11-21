package dev.whizzle.logic.client.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws what {@link NamePicker} works out: the list under a name being typed, the panel for hovering a name, and a
 * name's terms as chips. Everything goes through {@link Painter}, and sizes come from the text, so the layout is
 * tested (and drawn to a picture) without the game.
 */
public final class NamePanel {
	private NamePanel() {}

	static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFB8C0CC, DIM = 0xFF8A93A3, GREEN = 0xFF6EE07A, RED = 0xFFFF8F8F, GOLD = 0xFFFFC857,
		PURPLE = 0xFFD58CFF, BACK = 0xF41D1F25, HIGHLIGHT = 0xFF3A4458, EDGE = 0xFFFFFFFF, RULE = 0xFF3A3F4B;
	static final int ROW = 18, HEAD = 12, PAD = 4, LINE = 10, MIN_W = 170;

	/** A row's place on screen; {@code row} indexes {@link NamePicker.View#rows()}. */
	public record Hit(int x, int y, int w, int h, int row) {}

	/** Where the list goes. {@code legend}: its lines of symbol and meaning pairs, wrapped to fit. */
	public record Layout(int x, int y, int w, int h, List<Hit> hits, List<List<String[]>> legend) {
		public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }

		/** The row under the pointer, or -1. */
		public int rowAt(double mx, double my) {
			for (Hit hit : hits) if (mx >= hit.x() && mx < hit.x() + hit.w() && my >= hit.y() && my < hit.y() + hit.h()) return hit.row();
			return -1;
		}
	}

	private static int legendWidth(Painter p, List<String[]> line) {
		int w = 0;
		for (String[] pair : line) w += (w > 0 ? 10 : 0) + p.width(pair[0]) + 4 + p.width(pair[1]);
		return w;
	}

	/**
	 * Places the list under the box (above it when there's no room below), kept inside the screen. Its width fits the
	 * widest row and the symbols line; on a screen too narrow for that the symbols wrap.
	 */
	public static Layout layout(Painter p, NamePicker.View view, int boxX, int boxY, int boxH, int screenW, int screenH) {
		int inner = MIN_W;
		for (NamePicker.Section s : view.sections()) {
			inner = Math.max(inner, p.width(s.title().toUpperCase(java.util.Locale.ROOT)));
			for (NamePicker.Row r : s.rows()) inner = Math.max(inner, 20 + p.width(r.label()) + (r.note().isEmpty() ? 0 : 12 + p.width(r.note())));
		}
		inner = Math.max(inner, legendWidth(p, List.of(NamePicker.LEGEND)));   // the symbols on one line, when the screen allows
		inner = Math.max(40, Math.min(inner, screenW - 8 - 2 * PAD));
		List<List<String[]>> legend = new ArrayList<>();
		List<String[]> line = new ArrayList<>();
		for (String[] pair : NamePicker.LEGEND) {
			line.add(pair);
			if (legendWidth(p, line) > inner && line.size() > 1) {
				line.remove(line.size() - 1);
				legend.add(line);
				line = new ArrayList<>();
				line.add(pair);
			}
		}
		legend.add(line);
		int w = inner + 2 * PAD;
		int h = PAD;
		for (NamePicker.Section s : view.sections()) h += HEAD + s.rows().size() * ROW;
		h += 3 + legend.size() * LINE + PAD;
		int x = Math.max(4, Math.min(boxX, screenW - w - 4));
		int y = boxY + boxH + 2;
		if (y + h > screenH - 2) y = boxY - h - 2 >= 2 ? boxY - h - 2 : Math.max(2, screenH - h - 2);
		List<Hit> hits = new ArrayList<>();
		int cy = y + PAD, index = 0;
		for (NamePicker.Section s : view.sections()) {
			cy += HEAD;
			for (NamePicker.Row r : s.rows()) {
				if (r.value() != null) hits.add(new Hit(x + 1, cy, w - 2, ROW, index));
				index++;
				cy += ROW;
			}
		}
		return new Layout(x, y, w, h, hits, legend);
	}

	/** {@code highlight}: the row to show as chosen, or -1. */
	public static void draw(Painter p, NamePicker.View view, Layout l, boolean block, int highlight) {
		frame(p, l.x(), l.y(), l.w(), l.h(), BACK, EDGE);
		int inner = l.w() - 2 * PAD, x = l.x() + PAD, y = l.y() + PAD, index = 0;
		for (NamePicker.Section s : view.sections()) {
			p.text(p.fit(s.title().toUpperCase(java.util.Locale.ROOT), inner), x, y + 2, DIM);
			y += HEAD;
			for (NamePicker.Row r : s.rows()) {
				if (index == highlight) p.fill(l.x() + 1, y, l.x() + l.w() - 1, y + ROW, HIGHLIGHT);
				if (r.icon() != null) p.icon(r.icon(), block, x, y + 1);
				int noteW = r.note().isEmpty() ? 0 : p.width(r.note());
				String label = p.fit(r.label(), inner - 20 - (noteW > 0 ? noteW + 8 : 0));
				int color = r.state() == NamePicker.State.LEFT_OUT ? RED : r.special() ? PURPLE : r.value() == null ? GRAY : WHITE;
				p.text(label, x + 20, y + 5, color);
				if (r.state() == NamePicker.State.LEFT_OUT) p.fill(x + 20, y + 9, x + 20 + p.width(label), y + 10, RED);
				if (noteW > 0) p.text(r.note(), x + inner - noteW, y + 5,
					r.state() == NamePicker.State.MATCH ? GREEN : r.state() == NamePicker.State.LEFT_OUT ? RED : DIM);
				index++;
				y += ROW;
			}
		}
		p.fill(x, y + 1, x + inner, y + 2, RULE);
		y += 4;
		for (List<String[]> line : l.legend()) {
			int lx = x;
			for (String[] pair : line) {
				p.text(pair[0], lx, y, WHITE);
				lx += p.width(pair[0]) + 4;
				p.text(pair[1], lx, y, GRAY);
				lx += p.width(pair[1]) + 10;
			}
			y += LINE;
		}
	}

	private static void frame(Painter p, int x, int y, int w, int h, int back, int edge) {
		p.fill(x, y, x + w, y + h, edge);
		p.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
		p.fill(x + 1, y + 1, x + w - 1, y + h - 1, back);
	}

	// ---- hovering a name ----

	/** The lines of a hover panel, wrapped; worked out once so its size is known before drawing. */
	private static List<String> wrap(Painter p, String text, int w) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && p.width(line + " " + word) > w) { lines.add(line.toString()); line.setLength(0); }
			line.append(line.length() > 0 ? " " : "").append(word);
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	/** Draws what a name matches next to the pointer, kept inside the screen. */
	public static void hover(Painter p, NamePicker.Summary s, boolean block, int mouseX, int mouseY, int screenW, int screenH) {
		String what = block ? " blocks" : " items";
		String title = s.count() == 0 ? "Matches nothing" : "Matches " + s.count() + (s.count() == 1 ? what.substring(0, what.length() - 1) : what);
		int more = s.count() - s.sample().size();
		int iconsW = s.sample().size() * 18 + (more > 0 ? 2 + p.width("+" + more) : 0);
		int inner = Math.max(Math.max(p.width(title), iconsW), 150);
		List<String> out = new ArrayList<>();
		if (s.leftOutCount() > 0) {
			int rest = s.leftOutCount() - s.leftOut().size();
			out = wrap(p, "Left out: " + String.join(", ", s.leftOut()) + (rest > 0 ? " and " + rest + " more" : ""), inner);
		}
		int w = inner + 2 * PAD + 2, h = PAD + LINE + (s.sample().isEmpty() ? 0 : 20) + out.size() * LINE + PAD;
		int x = mouseX + 10, y = mouseY + 12;
		if (x + w > screenW - 2) x = Math.max(2, mouseX - w - 6);
		if (y + h > screenH - 2) y = Math.max(2, mouseY - h - 6);
		frame(p, x, y, w, h, 0xF0100010, 0xFF5000A0);
		int cx = x + PAD + 1, cy = y + PAD;
		p.text(title, cx, cy, s.count() == 0 ? RED : GOLD);
		cy += LINE;
		if (!s.sample().isEmpty()) {
			int ix = cx;
			for (String id : s.sample()) { p.icon(id, block, ix, cy + 1); ix += 18; }
			if (more > 0) p.text("+" + more, ix + 2, cy + 5, GRAY);
			cy += 20;
		}
		for (String line : out) { p.text(line, cx, cy, GRAY); cy += LINE; }
	}

	// ---- chips ----

	static final int CHIP_PATTERN = 0xFFD58CFF, CHIP_TAG = 0xFFB9A0FF, CHIP_EXCLUDE = 0xFFFF8F8F, CHIP_PLAIN = 0xFFE4E6EA, CHIP_UNKNOWN = 0xFF6A6F7A,
		CHIP_TEXT = 0xFF17131C;

	/**
	 * Draws a name's terms as chips inside a box at x, y of size w by h (covering the box's own text): purple for a
	 * wildcard or tag, light for a plain name, red and struck through for an exclusion, grey for one that stands for
	 * nothing. What doesn't fit ends in "+2". {@code ok}: whether each term stands for something; {@code dim}: for a box
	 * that can't be edited.
	 */
	public static void chips(Painter p, List<NameText.Term> terms, List<Boolean> ok, int x, int y, int w, int h, boolean dim) {
		p.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF000000);
		int cx = x + 3, right = x + w - 3, top = y + (h - 12) / 2;
		for (int i = 0; i < terms.size(); i++) {
			NameText.Term t = terms.get(i);
			String label = t.label();
			int left = terms.size() - i - 1;
			int reserve = left > 0 ? p.width("+" + left) + 3 : 0;
			int room = right - cx - reserve;
			if (i > 0 && p.width(label) + 6 > room) { p.text("+" + (left + 1), cx + 1, top + 2, GRAY); return; }
			label = p.fit(label, Math.max(6, room - 6));
			int cw = p.width(label) + 6;
			int color = !ok.get(i) ? CHIP_UNKNOWN : switch (t.kind()) {
				case PATTERN -> CHIP_PATTERN; case TAG -> CHIP_TAG; case EXCLUDE -> CHIP_EXCLUDE; case PLAIN -> CHIP_PLAIN; };
			if (dim) color = dim(color);
			p.fill(cx + 1, top, cx + cw - 1, top + 12, color);
			p.fill(cx, top + 1, cx + cw, top + 11, color);
			p.plain(label, cx + 3, top + 2, ok.get(i) ? CHIP_TEXT : WHITE);
			if (t.kind() == NameText.Kind.EXCLUDE) p.fill(cx + 2, top + 6, cx + cw - 2, top + 7, CHIP_TEXT);
			cx += cw + 2;
		}
	}

	private static int dim(int argb) {
		int r = (argb >> 16 & 0xFF) * 2 / 3, g = (argb >> 8 & 0xFF) * 2 / 3, b = (argb & 0xFF) * 2 / 3;
		return 0xFF000000 | r << 16 | g << 8 | b;
	}
}
