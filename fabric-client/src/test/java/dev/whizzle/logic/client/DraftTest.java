package dev.whizzle.logic.client;

import dev.whizzle.logic.client.net.Messages;
import dev.whizzle.logic.client.ui.Draft;
import dev.whizzle.logic.model.*;
import dev.whizzle.logic.model.Condition.Op;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The editor's working copy and the client's protocol decoding (no Minecraft needed). */
class DraftTest {
	static final Set<String> BLOCKS = Set.of("red_wool", "blue_wool", "stone"), ITEMS = Set.of("coal", "red_wool");

	static Program sample() {
		return new Program(List.of(
			new Program.Rule(List.of(Condition.block(Side.NORTH, true, "red_wool"), Condition.signal(Side.ANY, Op.GE, 3)), Action.set(15)),
			new Program.Rule(List.of(Condition.items(Side.DOWN, "coal", Op.LT, 10)), Action.pulse(7, 40)),
			new Program.Rule(List.of(Condition.players(6, Op.GE, 1)), Action.toggle(9))), Action.keep());
	}

	Draft.Built build(Draft d) { return d.build(BLOCKS::contains, ITEMS::contains); }

	@Test void aProgramSurvivesTheEditorUnchanged() {
		assertEquals(sample(), build(new Draft(sample())).program());
		assertEquals(Program.EMPTY, build(new Draft(Program.EMPTY)).program());
	}

	@Test void editsBecomeAProgram() {
		Draft d = new Draft();
		Draft.Rule r = new Draft.Rule();             // defaults: north is red_wool -> output 15
		d.rules.add(r);
		r.conditions.get(0).setKind(Condition.Kind.SIGNAL);
		r.conditions.get(0).number = "4";
		r.action = Action.Kind.PULSE;
		r.ticks = "20";
		d.otherwiseStrength = "2";
		Program p = build(d).program();
		assertEquals(Condition.signal(Side.NORTH, Op.GE, 4), p.rules().get(0).conditions().get(0));
		assertEquals(Action.pulse(15, 20), p.rules().get(0).action());
		assertEquals(Action.set(2), p.otherwise());
	}

	@Test void switchingKindKeepsTheRowValid() {
		Draft.Cond c = new Draft.Cond();
		for (Condition.Kind k : Condition.Kind.values()) {
			c.setKind(k);
			assertNull(c.ops().contains(c.op) ? null : "op", "op fits " + k);
			assertTrue(c.sides().contains(c.side) || k == Condition.Kind.PLAYERS, "side fits " + k);
		}
		c.setKind(Condition.Kind.SIGNAL); c.side = Side.ANY;
		c.setKind(Condition.Kind.BLOCK);
		assertEquals(Side.NORTH, c.side, "block checks need a real side");
		c.setKind(Condition.Kind.ITEMS);
		assertEquals(List.of(Side.FACES), c.sides());
	}

	@Test void problemsAreCaughtBeforeSaving() {
		Draft d = new Draft(sample());
		d.rules.get(0).conditions.get(0).id = "red_woll";
		assertEquals("Rule 1: no block called \"red_woll\"", build(d).problem());
		d = new Draft(sample());
		d.rules.get(1).conditions.get(0).id = "coall";
		assertTrue(build(d).problem().contains("no item called"));
		d = new Draft(sample());
		d.rules.get(0).strength = "16";
		assertTrue(build(d).problem().contains("0 to 15"));
		d = new Draft(sample());
		d.rules.get(0).strength = "";
		assertNotNull(build(d).problem(), "empty number");
		d = new Draft(sample());
		d.rules.get(1).conditions.get(0).id = "*";
		assertNull(build(d).problem(), "* means any item");
	}

	@Test void cyclingWraps() {
		assertEquals(Side.NORTH, Draft.next(List.of(Side.FACES), Side.DOWN));
		assertEquals(Condition.Op.IS_NOT, Draft.next(List.of(Condition.Op.IS, Condition.Op.IS_NOT), Condition.Op.IS));
	}

	@Test void decodesWhatThePluginSends() {
		String[] blocks = {"red_wool", "air", "air", "redstone_wire", "air", "chest"};
		List<Map<String, Integer>> containers = new ArrayList<>(Collections.nCopies(6, null));
		containers.set(5, Map.of("coal", 3));
		Snapshot snap = new Snapshot(blocks, new int[]{0, 0, 0, 7, 0, 0}, containers, new int[]{25}, 15, 0);
		// Byte layout written by the plugin's ClientBridge.open
		byte[] open = new Wire.Writer().int32(-5).int32(64).int32(300).bool(true).utf("Alex").bytes(sample().encode()).bytes(snap.encode()).bytes();
		Messages.Open o = Messages.open(open);
		assertEquals(-5, o.x()); assertEquals(300, o.z()); assertTrue(o.editable()); assertEquals("Alex", o.owner());
		assertEquals(sample(), o.program());
		assertEquals("redstone_wire", o.snapshot().block(Side.WEST));
		assertEquals(3, o.snapshot().items(Side.DOWN, "coal"));
		assertEquals(7, o.snapshot().signal(Side.ANY));
		byte[] live = new Wire.Writer().int32(1).int32(2).int32(3).bytes(snap.encode()).bytes();
		assertEquals(15, Messages.live(live).snapshot().output());
		byte[] save = Messages.save(1, 2, 3, sample());
		Wire.Reader in = new Wire.Reader(save);
		assertEquals(1, in.int32()); assertEquals(2, in.int32()); assertEquals(3, in.int32());
		assertEquals(sample(), Program.decode(in.bytes(Program.MAX_BYTES)));
		assertEquals(Messages.PROTOCOL, Messages.welcome(new Wire.Writer().varInt(1).bytes()));
	}
}
