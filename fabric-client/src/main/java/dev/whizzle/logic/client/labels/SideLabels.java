package dev.whizzle.logic.client.labels;

import dev.whizzle.logic.model.Side;

import java.util.List;

/**
 * Where the N, E, S, W labels go around a Logic Block (pure Java). Each letter floats just above the block's top,
 * near the edge of its side, so it stays inside the block's own column: a block next to the Logic Block never covers
 * it. North is red, like a compass needle.
 */
public final class SideLabels {
	/** Height of the label centres above the block's base (a daylight detector is 6/16 tall). */
	public static final double HEIGHT = 0.62;
	/** How far from the block's centre towards each side the labels sit. */
	public static final double OFFSET = 0.36;
	public static final int NORTH_COLOR = 0xFFFF5555, OTHER_COLOR = 0xFFFFFFFF;
	public static final List<Side> SIDES = List.of(Side.NORTH, Side.EAST, Side.SOUTH, Side.WEST);

	public record Label(Side side, String text, double x, double y, double z, int color) {}

	private SideLabels() {}

	public static List<Label> around(int x, int y, int z) {
		return SIDES.stream()
			.map(s -> new Label(s, s.name().substring(0, 1),
				x + 0.5 + s.dx * OFFSET, y + HEIGHT, z + 0.5 + s.dz * OFFSET,
				s == Side.NORTH ? NORTH_COLOR : OTHER_COLOR))
			.toList();
	}
}
