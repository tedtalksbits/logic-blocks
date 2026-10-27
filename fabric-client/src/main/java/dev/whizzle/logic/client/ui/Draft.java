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
				case CHANGE -> { op = Condition.Op.EQ; if (!number.equals("0")) number = "1"; }
			}
		}

		/** CHANGE: turns on (true) or off. */
		public boolean turnsOn() { return !number.equals("0"); }
		public void flipTurn() { number = turnsOn() ? "0" : "1"; }
		private static boolean itemLike(String id) { return !id.isBlank(); }

		public List<Side> sides() {
			return kind == Condition.Kind.SIGNAL || kind == Condition.Kind.CHANGE ? List.of(Side.values()) : List.of(Side.FACES);
		}
		public List<Condition.Op> ops() {
			return kind == Condition.Kind.BLOCK ? List.of(Condition.Op.IS, Condition.Op.IS_NOT)
				: kind == Condition.Kind.CHANGE ? List.of(Condition.Op.EQ)
				: List.of(Condition.Op.EQ, Condition.Op.NE, Condition.Op.LT, Condition.Op.LE, Condition.Op.GT, Condition.Op.GE);
		}

		public Condition toCondition() {
			return new Condition(kind, kind == Condition.Kind.PLAYERS ? Side.ANY : side, op, id, parse(number), parse(radius));
		}
	}

	/** A "move items" action: one {@code id} item ("*" = any) from the container on {@code from} to the one on {@code to}. */
	public static final class Move {
		public String id = "*";
		public Side from = Side.UP, to = Side.DOWN;

		public Move() {}
		Move(Action a) { id = a.id(); from = a.from(); to = a.to(); }

		Action toAction() { return Action.move(id, from, to); }
	}

	/**
	 * An output rule (its output action in {@code action}, {@code strength}, {@code ticks}, then any moves) or an
	 * "also" rule (moves only).
	 */
	public static final class Rule {
		public final List<Cond> conditions = new ArrayList<>();
		public Action.Kind action = Action.Kind.SET;
		public String strength = "15", ticks = "10";
		public final List<Move> moves = new ArrayList<>();

		/** A new output rule: north is red_wool -> output 15. */
		public Rule() { conditions.add(new Cond()); }
		/** A new "also" rule: always, move any item from up to down. */
		public static Rule also() { Rule r = new Rule(); r.conditions.clear(); r.moves.add(new Move()); return r; }

		Rule(Program.Rule r, boolean output) {
			for (Condition c : r.conditions()) conditions.add(new Cond(c));
			List<Action> as = r.actions();
			if (output) {
				action = as.getFirst().kind();
				strength = String.valueOf(as.getFirst().strength());
				ticks = String.valueOf(as.getFirst().ticks() > 0 ? as.getFirst().ticks() : 10);
			}
			for (int i = output ? 1 : 0; i < as.size(); i++) if (as.get(i).kind() == Action.Kind.MOVE) moves.add(new Move(as.get(i)));
		}
		Action toAction() { return new Action(action, parse(strength), action == Action.Kind.PULSE ? parse(ticks) : 0); }

		/** How many moves this rule may have: an output rule's first action is its output. */
		public static int maxMoves(boolean output) { return output ? Program.MAX_ACTIONS - 1 : Program.MAX_ACTIONS; }
	}

	public final List<Rule> rules = new ArrayList<>();
	public final List<Rule> also = new ArrayList<>();
	public boolean otherwiseKeep;
	public String otherwiseStrength = "0";

	public Draft() {}

	public Draft(Program p) {
		for (Program.Rule r : p.rules()) rules.add(new Rule(r, true));
		for (Program.Rule r : p.also()) also.add(new Rule(r, false));
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
		List<Program.Rule> out = new ArrayList<>(), outAlso = new ArrayList<>();
		for (int r = 0; r < rules.size(); r++) {
			String problem = buildRule(rules.get(r), true, "Rule " + (r + 1) + ": ", out, knownBlock, knownItem);
			if (problem != null) return new Built(null, problem);
		}
		for (int r = 0; r < also.size(); r++) {
			String problem = buildRule(also.get(r), false, "Also " + (r + 1) + ": ", outAlso, knownBlock, knownItem);
			if (problem != null) return new Built(null, problem);
		}
		Program p = new Program(out, otherwiseKeep ? Action.keep() : Action.set(parse(otherwiseStrength)), outAlso);
		String problem = p.problem();
		return problem == null ? new Built(p, null) : new Built(null, problem);
	}

	private static String buildRule(Rule rule, boolean output, String where, List<Program.Rule> out, Predicate<String> knownBlock, Predicate<String> knownItem) {
		List<Condition> cs = new ArrayList<>();
		for (Cond c : rule.conditions) {
			Condition cond = c.toCondition();
			if (cond.kind() == Condition.Kind.BLOCK && !knownBlock.test(cond.id())) return where + "no block called \"" + cond.id() + "\"";
			if (cond.kind() == Condition.Kind.ITEMS && !cond.id().equals("*") && !knownItem.test(cond.id())) return where + "no item called \"" + cond.id() + "\"";
			cs.add(cond);
		}
		List<Action> actions = new ArrayList<>();
		if (output) actions.add(rule.toAction());
		else if (rule.moves.isEmpty()) return where + "add something for it to do (+ Move)";
		for (Move m : rule.moves) {
			Action a = m.toAction();
			if (!a.id().equals("*") && !knownItem.test(a.id())) return where + "no item called \"" + a.id() + "\"";
			actions.add(a);
		}
		out.add(new Program.Rule(cs, actions));
		return null;
	}

	/** The next value in a list, wrapping around (used by the cycling buttons). */
	public static <T> T next(List<T> values, T current) {
		int i = values.indexOf(current);
		return values.get((i + 1) % values.size());
	}
}
