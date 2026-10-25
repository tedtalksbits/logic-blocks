package dev.whizzle.logic.client.labels;

import dev.whizzle.logic.client.net.Messages;
import dev.whizzle.logic.model.Side;
import dev.whizzle.logic.model.Wire;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Side labels: where the letters go, when the server is asked, and the query messages (no Minecraft needed). */
class SideLabelsTest {
	@Test void lettersSitAboveTheBlockTowardsTheirCompassSide() {
		List<SideLabels.Label> labels = SideLabels.around(10, 64, -3, LabelStyle.DEFAULT);
		assertEquals(List.of("N", "E", "S", "W"), labels.stream().map(SideLabels.Label::text).toList());
		SideLabels.Label n = labels.get(0), e = labels.get(1), s = labels.get(2), w = labels.get(3);
		assertTrue(n.z() < -3 + 0.5 && n.x() == 10.5, "north is towards -z");
		assertTrue(s.z() > -3 + 0.5 && s.x() == 10.5, "south is towards +z");
		assertTrue(e.x() > 10.5 && e.z() == -2.5, "east is towards +x");
		assertTrue(w.x() < 10.5 && w.z() == -2.5, "west is towards -x");
		for (SideLabels.Label l : labels) {
			assertTrue(l.y() > 64 + 6 / 16.0 && l.y() < 65, "above the daylight detector, below the block over it");
			assertTrue(l.x() > 10 && l.x() < 11 && l.z() > -3 && l.z() < -2, "inside the block's own column");
		}
		assertEquals(0xFFFF5555, n.color(), "north stands out in red");
		assertEquals(0xFFFFFFFF, e.color());
		assertEquals(Side.WEST, w.side());
	}

	@Test void styleMovesAndRecoloursTheLetters() {
		LabelStyle style = LabelStyle.DEFAULT.withHeight(1.2).withOffset(0).withNorthColor(0x55FFFF).withOtherColor(0xFFAA00);
		for (SideLabels.Label l : SideLabels.around(0, 10, 0, style)) {
			assertEquals(11.2, l.y(), 1e-9);
			assertEquals(0.5, l.x(), 1e-9); assertEquals(0.5, l.z(), 1e-9);
			assertEquals(l.side() == Side.NORTH ? 0xFF55FFFF : 0xFFFFAA00, l.color());
		}
	}

	@Test void previewViewsMatchTheWorld() {
		LabelStyle style = LabelStyle.DEFAULT;
		List<SideLabels.Glyph> top = SideLabels.topView(style, 100, 50, 64);
		SideLabels.Glyph n = top.get(0), e = top.get(1), s = top.get(2), w = top.get(3);
		assertTrue(n.y() < 82 && s.y() > 82 && n.x() == 132, "north up, south down");
		assertTrue(e.x() > 132 && w.x() < 132 && e.y() == 82, "east right, west left");
		assertEquals(SideLabels.FONT_PIXEL * 64, n.scale(), 1e-9, "letters scale with the block like in the world");
		assertEquals(2 * SideLabels.FONT_PIXEL * 64, SideLabels.topView(style.withSize(2), 100, 50, 64).get(0).scale(), 1e-9);

		List<SideLabels.Glyph> side = SideLabels.sideView(style, 100, 200, 64);
		assertEquals(List.of(Side.NORTH, Side.WEST, Side.EAST, Side.SOUTH), side.stream().map(SideLabels.Glyph::side).toList(),
			"north drawn first, behind south");
		for (SideLabels.Glyph g : side) assertEquals(200 - style.height() * 64, g.y(), 1e-9, "height above the base");
		assertTrue(side.get(1).x() < 132 && side.get(2).x() > 132, "from the south: west on the left");
		assertEquals(side.get(0).x(), side.get(3).x(), 1e-9, "north hides behind south");
	}

	@Test void asksOnceThenRemembersThenRefreshes() {
		LogicBlockLookup lookup = new LogicBlockLookup();
		List<Long> asked = new ArrayList<>();
		assertFalse(lookup.isLogic(7, 0, asked::add), "unknown: no labels yet");
		assertFalse(lookup.isLogic(7, 1, asked::add));
		assertEquals(List.of(7L), asked, "one question while waiting");

		lookup.answer(7, true, 3);
		assertTrue(lookup.isLogic(7, 4, asked::add));
		assertTrue(lookup.isLogic(7, 3 + LogicBlockLookup.FRESH_TICKS - 1, asked::add));
		assertEquals(1, asked.size(), "fresh answers aren't re-asked");

		long stale = 3 + LogicBlockLookup.FRESH_TICKS;
		assertTrue(lookup.isLogic(7, stale, asked::add), "a stale yes still shows while refreshing (no flicker)");
		assertEquals(2, asked.size(), "and asks again");
		lookup.answer(7, false, stale + 2);
		assertFalse(lookup.isLogic(7, stale + 3, asked::add), "the block was swapped for a plain daylight detector");
	}

	@Test void retriesWhenTheServerNeverAnswers() {
		LogicBlockLookup lookup = new LogicBlockLookup();
		List<Long> asked = new ArrayList<>();
		for (int t = 0; t < LogicBlockLookup.RETRY_TICKS * 3; t++) lookup.isLogic(1, t, asked::add);
		assertEquals(3, asked.size(), "about once a second, not every tick");
	}

	@Test void forgetAndClearDropAnswers() {
		LogicBlockLookup lookup = new LogicBlockLookup();
		List<Long> asked = new ArrayList<>();
		lookup.answer(5, true, 0);
		lookup.forget(5);
		assertFalse(lookup.isLogic(5, 1, asked::add), "broken: no labels");
		assertEquals(List.of(5L), asked, "and asks again if a detector appears there");
		lookup.answer(6, true, 0);
		lookup.clear();
		assertFalse(lookup.isLogic(6, 1, p -> {}));
	}

	@Test void cacheStaysSmall() {
		LogicBlockLookup lookup = new LogicBlockLookup();
		for (int i = 0; i < LogicBlockLookup.MAX_ENTRIES * 3; i++) lookup.answer(i, true, 0);
		assertTrue(lookup.size() <= LogicBlockLookup.MAX_ENTRIES);
	}

	@Test void queryAndAnswerMessages() {
		Wire.Reader q = new Wire.Reader(Messages.query(-4, 70, 123456));
		assertEquals(-4, q.int32()); assertEquals(70, q.int32()); assertEquals(123456, q.int32()); q.end();
		Messages.Is is = Messages.is(new Wire.Writer().int32(1).int32(-2).int32(3).bool(true).bytes());
		assertEquals(new Messages.Is(1, -2, 3, true), is);
		assertThrows(IllegalArgumentException.class, () -> Messages.is(new Wire.Writer().int32(1).bytes()));
		assertTrue(Messages.SERVERBOUND.contains(Messages.QUERY) && Messages.CLIENTBOUND.contains(Messages.IS));
	}
}
