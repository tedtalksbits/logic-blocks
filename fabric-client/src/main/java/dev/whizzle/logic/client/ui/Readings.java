package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.model.Action;
import dev.whizzle.logic.model.Condition;
import dev.whizzle.logic.model.Engine;
import dev.whizzle.logic.model.Program;
import dev.whizzle.logic.model.Side;
import dev.whizzle.logic.model.Snapshot;

import java.util.List;
import java.util.Locale;

/**
 * What the rules being edited would do right now, from what the block sees: each condition's reading and whether it
 * holds, which rule applies, what an action would work on. The same {@link Engine} the server runs, on the draft, so
 * edits are checked before they're saved (pure Java, unit-tested).
 */
public final class Readings {
	private Readings() {}

	public record Check(boolean ok, String reading) {}

	/** {@code before}: the snapshot before {@code now}, for "a signal turns on" (may be null). */
	public static Check check(Draft.Cond c, Snapshot now, Snapshot before) {
		Condition cond = c.toCondition();
		Program one = new Program(List.of(new Program.Rule(List.of(cond), Action.set(0))), Action.set(0));
		int[] was = null;
		if (cond.kind() == Condition.Kind.CHANGE && before != null) {
			was = Engine.unknownSignals();
			was[cond.side().ordinal()] = before.signal(cond.side());
		}
		Engine.Evaluation e = Engine.evaluate(one, now, was);
		return new Check(e.truth()[0][0] && cond.problem() == null, e.readings()[0][0]);
	}

	public static boolean allTrue(Draft.Rule rule, Snapshot now, Snapshot before) {
		for (Draft.Cond c : rule.conditions) if (!check(c, now, before).ok()) return false;
		return true;
	}

	/** The first If/Else rule whose conditions all hold right now, or -1. */
	public static int matched(Draft draft, Snapshot now, Snapshot before) {
		for (int r = 0; r < draft.rules.size(); r++) if (allTrue(draft.rules.get(r), now, before)) return r;
		return -1;
	}

	/** What an extra action works on, right now: a move's containers, a counter's value, a timer's state. */
	public static String extra(Draft.Extra ex, Snapshot now) {
		if (ex.kind.counter()) {
			int i = Condition.counterIndex(ex.id);
			return i < 0 ? "" : Condition.memoryLabel(ex.id) + " = " + now.counter(i);
		}
		if (ex.kind == Action.Kind.START || ex.kind == Action.Kind.STOP) {
			int i = Condition.timerIndex(ex.id);
			return i < 0 ? "" : Condition.memoryLabel(ex.id) + " " + timer(now, i);
		}
		return move(ex, now);
	}

	public static String timer(Snapshot now, int i) {
		int left = now.timerLeft(i);
		return left > 0 ? "running, " + Action.seconds(left) + " left" : "not running";
	}

	/** What a move would see now: "up: hopper, 3 diamond -> down: chest". */
	public static String move(Draft.Extra m, Snapshot now) {
		var from = now.containers().get(m.from.ordinal());
		var to = now.containers().get(m.to.ordinal());
		String fromText = m.from.label().toLowerCase(Locale.ROOT) + ": " + now.block(m.from)
			+ (from == null ? " (no container)" : ", " + now.items(m.from, m.id) + " " + (m.id.equals("*") ? "items" : m.id));
		String toText = m.to.label().toLowerCase(Locale.ROOT) + ": " + now.block(m.to) + (to == null ? " (no container)" : "");
		return fromText + " -> " + toText;
	}

	/** Whether a move has a container on both of its sides. */
	public static boolean moveReady(Draft.Extra m, Snapshot now) {
		return now.containers().get(m.from.ordinal()) != null && now.containers().get(m.to.ordinal()) != null && m.from != m.to;
	}

	/** Dust touching the block carries its own output back in; suggest a repeater when a signal rule reads such a side. */
	public static String dustWarning(Draft.Rule rule, Snapshot now) {
		for (Draft.Cond c : rule.conditions) {
			if (c.kind != Condition.Kind.SIGNAL && c.kind != Condition.Kind.CHANGE) continue;
			for (Side s : c.side == Side.ANY ? List.of(Side.FACES) : List.of(c.side))
				if (now.block(s).equals("redstone_wire"))
					return "! Dust on the " + s.label().toLowerCase(Locale.ROOT) + " side also carries this block's own output back in. Feed inputs through a repeater.";
		}
		return null;
	}

	/** What's really on a side, to check a name against: the container's items with their counts, or the block there (count -1). */
	public static java.util.Map<String, Integer> here(boolean block, Side side, Snapshot now) {
		if (!block) return side == Side.ANY ? null : now.containers().get(side.ordinal());
		java.util.Map<String, Integer> blocks = new java.util.LinkedHashMap<>();
		for (Side s : side == Side.ANY ? List.of(Side.FACES) : List.of(side)) if (!now.block(s).isEmpty()) blocks.put(now.block(s), -1);
		return blocks;
	}

	/** The most plentiful item in the container on a side, if it isn't {@code current}; else null. */
	public static String mostIn(Side side, String current, Snapshot now) {
		if (side == Side.ANY) return null;
		var container = now.containers().get(side.ordinal());
		if (container == null || container.isEmpty()) return null;
		String most = container.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).get().getKey();
		return most.equals(current) ? null : most;
	}
}
