package dev.whizzle.logic.client.ui;

/**
 * The few drawing calls the editor's own panels need, so they can be drawn in the game and, in tests, to a picture
 * (the layout is checked without starting Minecraft). Colours are ARGB.
 */
public interface Painter extends Measure {
	void fill(int x1, int y1, int x2, int y2, int argb);

	/** Text with the game's drop shadow. */
	void text(String s, int x, int y, int argb);

	/** Text without a shadow, for dark text on a light chip. */
	void plain(String s, int x, int y, int argb);

	/** The 16x16 icon of a block or item, by id; nothing if it has none. */
	void icon(String id, boolean block, int x, int y);
}
