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
		List<SideLabels.Label> labels = SideLabels.around(10, 64, -3);
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
		assertEquals(SideLabels.NORTH_COLOR, n.color(), "north stands out");
		assertEquals(SideLabels.OTHER_COLOR, e.color());
		assertEquals(Side.WEST, w.side());
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
