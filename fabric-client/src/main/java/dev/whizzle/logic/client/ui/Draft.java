package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.model.Action;
import dev.whizzle.logic.model.Condition;
import dev.whizzle.logic.model.Program;
import dev.whizzle.logic.model.Side;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * The editor's working copy of a program: plain mutable fields with text for every box, so half-typed values never
 * get lost. {@link #build} turns it back into a {@link Program} or says what's wrong (pure Java, unit-tested).
 */
public final class Draft {
	public static final class Cond {
		public Condition.Kind kind = Condition.Kind.BLOCK;
		public Side side = Side.NORTH;
		public Condition.Op op = Condition.Op.IS;
		public String id = "red_wool", number = "1", radius = "5";

		public Cond() {}
		Cond(Condition c) {
			kind = c.kind(); side = c.side(); op = c.op(); id = c.id();
			number = String.valueOf(c.number()); radius = String.valueOf(Math.max(1, c.number2()));
			if (id.isEmpty()) id = kind == Condition.Kind.ITEMS ? "*" : "red_wool";
		}

		/** Switching kind picks sensible defaults so the row is valid straight away. */
		public void setKind(Condition.Kind k) {
			kind = k;
			switch (k) {
				case BLOCK -> { if (side == Side.ANY) side = Side.NORTH; op = op == Condition.Op.IS_NOT ? op : Condition.Op.IS; if (id.equals("*")) id = "red_wool"; }
				case SIGNAL -> { op = op.numeric() ? op : Condition.Op.GE; }
				case ITEMS -> { if (side == Side.ANY) side = Side.DOWN; op = op.numeric() ? op : Condition.Op.GE; if (!itemLike(id)) id = "*"; }
				case PLAYERS -> { op = op.numeric() ? op : Condition.Op.GE; }
			}
		}
		private static boolean itemLike(String id) { return !id.isBlank(); }

		public List<Side> sides() {
			return kind == Condition.Kind.SIGNAL ? List.of(Side.values()) : List.of(Side.FACES);
		}
		public List<Condition.Op> ops() {
			return kind == Condition.Kind.BLOCK ? List.of(Condition.Op.IS, Condition.Op.IS_NOT)
				: List.of(Condition.Op.EQ, Condition.Op.NE, Condition.Op.LT, Condition.Op.LE, Condition.Op.GT, Condition.Op.GE);
		}

		Condition toCondition() {
			return new Condition(kind, kind == Condition.Kind.PLAYERS ? Side.ANY : side, op, id, parse(number), parse(radius));
		}
	}

	public static final class Rule {
		public final List<Cond> conditions = new ArrayList<>();
		public Action.Kind action = Action.Kind.SET;
		public String strength = "15", ticks = "10";

		public Rule() { conditions.add(new Cond()); }
		Rule(Program.Rule r) {
			for (Condition c : r.conditions()) conditions.add(new Cond(c));
			action = r.action().kind();
			strength = String.valueOf(r.action().strength());
			ticks = String.valueOf(r.action().ticks() > 0 ? r.action().ticks() : 10);
		}
		Action toAction() { return new Action(action, parse(strength), action == Action.Kind.PULSE ? parse(ticks) : 0); }
	}

	public final List<Rule> rules = new ArrayList<>();
	public boolean otherwiseKeep;
	public String otherwiseStrength = "0";

	public Draft() {}

	public Draft(Program p) {
		for (Program.Rule r : p.rules()) rules.add(new Rule(r));
		otherwiseKeep = p.otherwise().kind() == Action.Kind.KEEP;
		otherwiseStrength = String.valueOf(p.otherwise().strength());
	}

	static int parse(String s) {
		try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return -1; }
	}

	/** Result of building: a program, or the first problem in words. */
	public record Built(Program program, String problem) {}

	/**
	 * Builds the program. {@code knownBlock}/{@code knownItem} check ids against the game's registries, so a typo
	 * like "red_woll" is caught before saving.
	 */
	public Built build(Predicate<String> knownBlock, Predicate<String> knownItem) {
		List<Program.Rule> out = new ArrayList<>();
		for (int r = 0; r < rules.size(); r++) {
			Rule rule = rules.get(r);
			List<Condition> cs = new ArrayList<>();
			for (Cond c : rule.conditions) {
				Condition cond = c.toCondition();
				if (cond.kind() == Condition.Kind.BLOCK && !knownBlock.test(cond.id())) return new Built(null, "Rule " + (r + 1) + ": no block called \"" + cond.id() + "\"");
				if (cond.kind() == Condition.Kind.ITEMS && !cond.id().equals("*") && !knownItem.test(cond.id())) return new Built(null, "Rule " + (r + 1) + ": no item called \"" + cond.id() + "\"");
				cs.add(cond);
			}
			out.add(new Program.Rule(cs, rule.toAction()));
		}
		Program p = new Program(out, otherwiseKeep ? Action.keep() : Action.set(parse(otherwiseStrength)));
		String problem = p.problem();
		return problem == null ? new Built(p, null) : new Built(null, problem);
	}

	/** The next value in a list, wrapping around (used by the cycling buttons). */
	public static <T> T next(List<T> values, T current) {
		int i = values.indexOf(current);
		return values.get((i + 1) % values.size());
	}
}
