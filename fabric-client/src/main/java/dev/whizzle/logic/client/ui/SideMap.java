package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.client.ui.EditorLayout.Option;
import dev.whizzle.logic.model.Side;

import java.util.ArrayList;
import java.util.List;

/**
 * Choosing a side from a map of the block: north at the top, west and east beside it, south below, up and down to
 * the right, each with the block that's there. So nobody has to work out which way north is.
 */
public final class SideMap {
	private SideMap() {}

	static final int CELL_W = 86, CELL_H = 28, GAP = 3, PAD = 4, HEAD = 12, ANY_H = 16;

	/** {@code cells}: x, y, w, h of each option, in the options' order. */
	public record Layout(int x, int y, int w, int h, List<int[]> cells, int[] self) {
		public boolean contains(double mx, double my) { return mx >= x && mx < x + w && my >= y && my < y + h; }

		public int indexAt(double mx, double my) {
			for (int i = 0; i < cells.size(); i++) {
				int[] c = cells.get(i);
				if (mx >= c[0] && mx < c[0] + c[2] && my >= c[1] && my < c[1] + c[3]) return i;
			}
			return -1;
		}
	}

	public static int width() { return 2 * PAD + 4 * CELL_W + 3 * GAP; }

	/** Column and row of a side on the map. */
	private static int[] place(Side s) {
		return switch (s) {
			case NORTH -> new int[] {1, 0}; case WEST -> new int[] {0, 1}; case EAST -> new int[] {2, 1}; case SOUTH -> new int[] {1, 2};
			case UP -> new int[] {3, 0}; case DOWN -> new int[] {3, 2}; case ANY -> new int[] {0, 3};
		};
	}

	/** Places the map under the button at ax, ay (above it when there's no room below), kept inside the screen. */
	public static Layout layout(List<Side> sides, int ax, int ay, int ah, int screenW, int screenH) {
		boolean any = sides.contains(Side.ANY);
		int w = width(), h = PAD + HEAD + 3 * CELL_H + 2 * GAP + (any ? GAP + ANY_H : 0) + PAD;
		int x = Math.max(4, Math.min(ax, screenW - w - 4));
		int y = ay + ah + 1;
		if (y + h > screenH - 2) y = ay - h - 1 >= 2 ? ay - h - 1 : Math.max(2, screenH - h - 2);
		int gx = x + PAD, gy = y + PAD + HEAD;
		List<int[]> cells = new ArrayList<>();
		for (Side s : sides) {
			int[] at = place(s);
			if (s == Side.ANY) cells.add(new int[] {gx, gy + 3 * (CELL_H + GAP), w - 2 * PAD, ANY_H});
			else cells.add(new int[] {gx + at[0] * (CELL_W + GAP), gy + at[1] * (CELL_H + GAP), CELL_W, CELL_H});
		}
		return new Layout(x, y, w, h, cells, new int[] {gx + CELL_W + GAP, gy + CELL_H + GAP, CELL_W, CELL_H});
	}

	/**
	 * @param options one per side, in {@code sides}' order: its name, what's there (note), that block's id (icon)
	 * @param you     the side the player stands on, or null
	 */
	public static void draw(Painter p, List<Side> sides, List<Option> options, Layout l, int current, int highlight, Side you) {
		NamePanel.frame(p, l.x(), l.y(), l.w(), l.h(), NamePanel.BACK, NamePanel.EDGE);
		p.text("Which side? North is at the top.", l.x() + PAD + 1, l.y() + PAD + 1, NamePanel.DIM);
		int[] self = l.self();
		p.fill(self[0], self[1], self[0] + self[2], self[1] + self[3], 0xFF14161B);
		String me = "this block";
		p.text(me, self[0] + (self[2] - p.width(me)) / 2, self[1] + 10, NamePanel.DIM);
		for (int i = 0; i < sides.size(); i++) {
			int[] c = l.cells().get(i);
			Option o = options.get(i);
			boolean on = i == current, hot = i == highlight;
			p.fill(c[0], c[1], c[0] + c[2], c[1] + c[3], on ? 0xFFFFFFFF : hot ? 0xFFB8C0CC : 0xFF5A5F6A);
			p.fill(c[0] + 1, c[1] + 1, c[0] + c[2] - 1, c[1] + c[3] - 1, on || hot ? NamePanel.HIGHLIGHT : 0xFF2B2F38);
			if (sides.get(i) == Side.ANY) {
				p.text(o.label(), c[0] + 6, c[1] + 4, on ? EditorLayout.AQUA : 0xFFFFFFFF);
				p.text(o.note(), c[0] + 12 + p.width(o.label()), c[1] + 4, NamePanel.DIM);
				continue;
			}
			if (o.icon() != null && !o.icon().isEmpty()) p.icon(o.icon(), true, c[0] + 3, c[1] + 6);
			p.text(o.label(), c[0] + 22, c[1] + 4, on ? EditorLayout.AQUA : 0xFFFFFFFF);
			if (sides.get(i) == you) p.text("you", c[0] + c[2] - 4 - p.width("you"), c[1] + 4, EditorLayout.GOLD);
			p.text(p.fit(o.note(), c[2] - 25), c[0] + 22, c[1] + 15, NamePanel.DIM);
		}
	}
}
