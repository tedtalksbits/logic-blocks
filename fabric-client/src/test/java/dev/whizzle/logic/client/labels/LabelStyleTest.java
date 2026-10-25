package dev.whizzle.logic.client.labels;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Label settings: ranges, the colour palette, and the settings file (no Minecraft needed). */
class LabelStyleTest {
	@Test void valuesStayInRange() {
		LabelStyle s = new LabelStyle(9, -1, 3, 0xFF123456, 0x12FFFFFF, 400);
		assertEquals(LabelStyle.MAX_SIZE, s.size());
		assertEquals(LabelStyle.MIN_HEIGHT, s.height());
		assertEquals(LabelStyle.MAX_OFFSET, s.offset());
		assertEquals(0x123456, s.northColor(), "alpha is ours to choose");
		assertEquals(0xFFFFFF, s.otherColor());
		assertEquals(100, s.background());
		assertEquals(LabelStyle.DEFAULT.size(), new LabelStyle(Double.NaN, 1, 0.2, 0, 0, 0).size(), "garbage falls back to the default");
	}

	@Test void defaultsMatchTheFirstVersion() {
		LabelStyle d = LabelStyle.DEFAULT;
		assertEquals(1.0, d.size()); assertEquals(0.62, d.height()); assertEquals(0.36, d.offset());
		assertEquals("Red", LabelStyle.colorName(d.northColor()));
		assertEquals("White", LabelStyle.colorName(d.otherColor()));
	}

	@Test void backgroundIsAnAlpha() {
		assertEquals(0, LabelStyle.DEFAULT.withBackground(0).backgroundArgb(), "none");
		assertEquals(0xFF000000, LabelStyle.DEFAULT.withBackground(100).backgroundArgb());
		assertEquals(0x40000000, LabelStyle.DEFAULT.withBackground(25).backgroundArgb(), "vanilla name-tag darkness");
	}

	@Test void colourButtonCyclesThroughThePalette() {
		Set<Integer> seen = new HashSet<>();
		int c = LabelStyle.DEFAULT.northColor();
		for (int i = 0; i < LabelStyle.COLORS.size(); i++) { assertTrue(seen.add(c), "no repeats in one lap"); c = LabelStyle.nextColor(c); }
		assertEquals(LabelStyle.DEFAULT.northColor(), c, "back to the start");
		assertEquals("#123456", LabelStyle.colorName(0x123456), "hand-edited colours show as hex");
		assertEquals(LabelStyle.COLORS.getFirst().rgb(), LabelStyle.nextColor(0x123456));
	}

	@Test void settingsFileRoundTripAndBadLines() {
		LabelStyle s = new LabelStyle(1.35, 0.9, 0.25, 0x55FFFF, 0xFFAA00, 60);
		Properties p = new Properties();
		s.store(p);
		assertEquals(s, LabelStyle.load(p));
		assertEquals("#55FFFF", p.getProperty("label-north-color"));

		Properties bad = new Properties();
		bad.setProperty("label-size", "huge");
		bad.setProperty("label-north-color", "red");
		bad.setProperty("label-height", "1.1");
		LabelStyle loaded = LabelStyle.load(bad);
		assertEquals(LabelStyle.DEFAULT.size(), loaded.size());
		assertEquals(LabelStyle.DEFAULT.northColor(), loaded.northColor());
		assertEquals(1.1, loaded.height(), "good lines still count");
		assertEquals(LabelStyle.DEFAULT, LabelStyle.load(new Properties()), "no file yet");
	}
}
