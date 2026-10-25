package dev.whizzle.logic.client.labels;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * How the side labels look, chosen by each player in the settings screen and kept in
 * {@code config/logicblocks.properties}. Pure Java: values are clamped to their ranges, and anything unreadable in
 * the file falls back to the default.
 *
 * @param size       letter size; 1 is vanilla name-tag size
 * @param height     height of the letters' centres above the block's base, in blocks (the block is 6/16 tall)
 * @param offset     distance from the block's centre towards each side, in blocks
 * @param northColor RGB of the N
 * @param otherColor RGB of E, S and W
 * @param background backing behind the letters, 0 (none) to 100 (opaque black) percent
 */
public record LabelStyle(double size, double height, double offset, int northColor, int otherColor, int background) {
	public static final double MIN_SIZE = 0.5, MAX_SIZE = 2.0;
	public static final double MIN_HEIGHT = 0.4, MAX_HEIGHT = 1.5;
	public static final double MIN_OFFSET = 0.0, MAX_OFFSET = 0.5;

	public record NamedColor(String name, int rgb) {}

	/** The chat colours, so every choice reads well on Minecraft's font. */
	public static final List<NamedColor> COLORS = List.of(
		new NamedColor("Red", 0xFF5555), new NamedColor("Gold", 0xFFAA00), new NamedColor("Yellow", 0xFFFF55),
		new NamedColor("Green", 0x55FF55), new NamedColor("Aqua", 0x55FFFF), new NamedColor("Blue", 0x5555FF),
		new NamedColor("Pink", 0xFF55FF), new NamedColor("White", 0xFFFFFF), new NamedColor("Gray", 0xAAAAAA),
		new NamedColor("Black", 0x000000));

	public static final LabelStyle DEFAULT = new LabelStyle(1.0, 0.62, 0.36, 0xFF5555, 0xFFFFFF, 25);

	public LabelStyle {
		size = clamp(size, MIN_SIZE, MAX_SIZE, DEFAULT_SIZE);
		height = clamp(height, MIN_HEIGHT, MAX_HEIGHT, DEFAULT_HEIGHT);
		offset = clamp(offset, MIN_OFFSET, MAX_OFFSET, DEFAULT_OFFSET);
		northColor &= 0xFFFFFF;
		otherColor &= 0xFFFFFF;
		background = Math.clamp(background, 0, 100);
	}

	// Record components can't refer to DEFAULT while it's being built, so the defaults are repeated here.
	private static final double DEFAULT_SIZE = 1.0, DEFAULT_HEIGHT = 0.62, DEFAULT_OFFSET = 0.36;

	private static double clamp(double v, double min, double max, double fallback) {
		return Double.isFinite(v) ? Math.clamp(v, min, max) : fallback;
	}

	public LabelStyle withSize(double v) { return new LabelStyle(v, height, offset, northColor, otherColor, background); }
	public LabelStyle withHeight(double v) { return new LabelStyle(size, v, offset, northColor, otherColor, background); }
	public LabelStyle withOffset(double v) { return new LabelStyle(size, height, v, northColor, otherColor, background); }
	public LabelStyle withNorthColor(int v) { return new LabelStyle(size, height, offset, v, otherColor, background); }
	public LabelStyle withOtherColor(int v) { return new LabelStyle(size, height, offset, northColor, v, background); }
	public LabelStyle withBackground(int v) { return new LabelStyle(size, height, offset, northColor, otherColor, v); }

	/** Opaque ARGB for drawing. */
	public static int argb(int rgb) { return 0xFF000000 | rgb; }

	/** ARGB of the backing, or 0 for none. */
	public int backgroundArgb() { return background == 0 ? 0 : Math.round(background * 2.55F) << 24; }

	/** The palette name of a colour, or its hex code if it was set by hand in the file. */
	public static String colorName(int rgb) {
		for (NamedColor c : COLORS) if (c.rgb() == (rgb & 0xFFFFFF)) return c.name();
		return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
	}

	/** The next palette colour after this one (the first one for a colour outside the palette). */
	public static int nextColor(int rgb) {
		for (int i = 0; i < COLORS.size(); i++)
			if (COLORS.get(i).rgb() == (rgb & 0xFFFFFF)) return COLORS.get((i + 1) % COLORS.size()).rgb();
		return COLORS.getFirst().rgb();
	}

	public void store(Properties p) {
		p.setProperty("label-size", fmt(size));
		p.setProperty("label-height", fmt(height));
		p.setProperty("label-offset", fmt(offset));
		p.setProperty("label-north-color", String.format(Locale.ROOT, "#%06X", northColor));
		p.setProperty("label-color", String.format(Locale.ROOT, "#%06X", otherColor));
		p.setProperty("label-background", Integer.toString(background));
	}

	public static LabelStyle load(Properties p) {
		LabelStyle d = DEFAULT;
		return new LabelStyle(
			number(p, "label-size", d.size), number(p, "label-height", d.height), number(p, "label-offset", d.offset),
			color(p, "label-north-color", d.northColor), color(p, "label-color", d.otherColor),
			(int) Math.round(number(p, "label-background", d.background)));
	}

	private static String fmt(double v) { return String.format(Locale.ROOT, "%.2f", v); }

	private static double number(Properties p, String key, double fallback) {
		try { return Double.parseDouble(p.getProperty(key, "").trim()); }
		catch (NumberFormatException e) { return fallback; }
	}

	private static int color(Properties p, String key, int fallback) {
		String v = p.getProperty(key, "").trim();
		if (v.startsWith("#")) v = v.substring(1);
		if (!v.matches("[0-9a-fA-F]{6}")) return fallback;
		return Integer.parseInt(v, 16);
	}
}
