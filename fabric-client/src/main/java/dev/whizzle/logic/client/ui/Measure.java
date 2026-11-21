package dev.whizzle.logic.client.ui;

/** How wide text is in the game's font, so the editor can be laid out (and its layout tested) without the game. */
public interface Measure {
	int width(String s);

	/** {@code s} cut to fit {@code w} pixels. */
	String fit(String s, int w);
}
