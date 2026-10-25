package dev.whizzle.logic.client.labels;

import dev.whizzle.logic.model.Side;

import java.util.List;

/**
 * Where the N, E, S, W labels go around a Logic Block, in the world and in the settings preview (pure Java). By
 * default each letter floats just above the block's top, near the edge of its side, inside the block's own column so
 * a neighbouring block never covers it. Players can move and recolour them ({@link LabelStyle}).
 */
public final class SideLabels {
	/** World size of one font pixel at label size 1: the same as a vanilla name tag. */
	public static final double FONT_PIXEL = 0.025;
	/** Height of the daylight detector a Logic Block is made of, in blocks. */
	public static final double BLOCK_HEIGHT = 6 / 16.0;
	public static final List<Side> SIDES = List.of(Side.NORTH, Side.EAST, Side.SOUTH, Side.WEST);

	/** A letter centred at x, y, z (world coordinates), in ARGB {@code color}. */
	public record Label(Side side, String text, double x, double y, double z, int color) {}

	/** A letter centred at x, y on screen, drawn {@code scale} screen pixels per font pixel. */
	public record Glyph(Side side, String text, double x, double y, double scale, int color) {}

	private SideLabels() {}

	public static String letter(Side s) { return s.name().substring(0, 1); }

	public static int color(Side s, LabelStyle style) { return LabelStyle.argb(s == Side.NORTH ? style.northColor() : style.otherColor()); }

	public static List<Label> around(int x, int y, int z, LabelStyle style) {
		return SIDES.stream()
			.map(s -> new Label(s, letter(s),
				x + 0.5 + s.dx * style.offset(), y + style.height(), z + 0.5 + s.dz * style.offset(), color(s, style)))
			.toList();
	}

	/**
	 * The settings preview's top view, north up (as the daylight detector's top texture is drawn): the block is the
	 * square at {@code left, top}, {@code blockPx} wide.
	 */
	public static List<Glyph> topView(LabelStyle style, double left, double top, double blockPx) {
		double cx = left + blockPx / 2, cy = top + blockPx / 2, scale = FONT_PIXEL * style.size() * blockPx;
		return SIDES.stream()
			.map(s -> new Glyph(s, letter(s), cx + s.dx * style.offset() * blockPx, cy + s.dz * style.offset() * blockPx, scale, color(s, style)))
			.toList();
	}

	/**
	 * The settings preview's side view, seen from the south: W on the left, E on the right, S in front of N. The
	 * block's base is at {@code baseY}, its left edge at {@code left}. Ordered back to front, so N is drawn first.
	 */
	public static List<Glyph> sideView(LabelStyle style, double left, double baseY, double blockPx) {
		double cx = left + blockPx / 2, y = baseY - style.height() * blockPx, scale = FONT_PIXEL * style.size() * blockPx;
		return List.of(Side.NORTH, Side.WEST, Side.EAST, Side.SOUTH).stream()
			.map(s -> new Glyph(s, letter(s), cx + s.dx * style.offset() * blockPx, y, scale, color(s, style)))
			.toList();
	}
}
