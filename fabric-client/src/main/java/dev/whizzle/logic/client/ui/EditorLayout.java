package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.model.Action;
import dev.whizzle.logic.model.Condition;
import dev.whizzle.logic.model.Presets;
import dev.whizzle.logic.model.Program;
import dev.whizzle.logic.model.Side;
import dev.whizzle.logic.model.Snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Where everything in the rule editor goes: every button, box, word and lamp, with its place, its text and what it
 * does to the {@link Draft}. Pure Java, so the layout is unit-tested (nothing overlaps, nothing leaves the screen) and
 * can be drawn to a picture without the game; {@link LogicScreen} only turns these into the game's widgets.
 *
 * <p>Left: the If/Else and When tabs with their rules, a lamp on each that applies right now. Right: the selected
 * rule, one row per condition (a lamp says whether it holds), then what the rule does. A new, empty block gets a
 * start screen instead. The right side scrolls when it's taller than the screen.
 */
public final class EditorLayout {
	private EditorLayout() {}

	static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFB8C0CC, DIM = 0xFF8A93A3, GREEN = 0xFF6EE07A, RED = 0xFFFF6B6B, GOLD = 0xFFFFC857,
		AQUA = 0xFF6FD3FF, PATTERN = 0xFFD58CFF, OFF_TAB = 0xFFA0A0A0;
	/** Height of a row of widgets, of a row with a name (icons and shortcuts under it), and the room kept for the icons. */
	public static final int LINE = 22, NAME_ROW = 40, STRIP_W = 94;

	/** Which list is open, what's selected in each and how far the right side is scrolled; kept by the screen across rebuilds. */
	public static final class State {
		public boolean alsoTab;
		public int selected, selectedAlso, scroll;
	}

	public enum Kind { BUTTON, CHOICE, MENU, CARD, NUMBER, DECIMAL, NAME, WORD, LAMP, FRAME, STRIP }

	/** What the screen does once an element's action has run: nothing, lay out again, or that and mark the rules changed. */
	public enum After { NOTHING, REBUILD, CHANGED }

	/** Things only the screen can do. */
	public enum Command { SAVE, CLOSE, COPY, PASTE, PRESETS, LABELS }

	/** A line of a list of options. {@code icon}: a block id to draw (the side map), or null. */
	public record Option(String label, String note, String icon, boolean enabled) {
		public Option(String label, String note) { this(label, note, null, true); }
	}

	/** {@code state}: 0 not true (red), 1 true (green), 2 neither (grey). {@code reading}: what the block sees, for hovering. */
	public record Lamp(int state, String reading) {}

	/** One thing on the screen. Which fields matter depends on {@link #kind}. */
	public static final class El {
		public final Kind kind;
		public final int x, w, h;
		public int y;
		/** A button's or word's text, a choice's current label, a card's title. */
		public String text = "";
		/** A card's second line; on a button, text after {@link #text} in white (an offer's "3 kinds"). */
		public String sub = "";
		public int color = WHITE;
		public boolean active = true;
		/** BUTTON: what pressing does to the draft or state. */
		public Runnable press;
		public Command command;
		public After after = After.NOTHING;
		/** CARD: which of {@link Presets#all()} it loads. */
		public int preset = -1;
		/** NUMBER, DECIMAL, NAME: the text to start with and where typing goes. */
		public String value = "";
		public Consumer<String> set;
		/** NAME: whether it names blocks (else items), and the side it's about. */
		public boolean block;
		public Side side = Side.ANY;
		/** CHOICE, MENU: the options (worked out when asked, so their notes are live), which is current, and what choosing does. */
		public Supplier<List<Option>> options;
		public int current = -1;
		public IntConsumer choose;
		public List<Command> commands;
		/** CHOICE: show the options as a map of the block's sides; {@link #sides} says which side each option is. */
		public boolean map;
		public List<Side> sides;
		/** CHOICE: a block id to draw on the button (what's on the chosen side), or null. */
		public Supplier<String> icon;
		/** LAMP. */
		public Supplier<Lamp> lamp;
		/** WORD: text and colour that change while the editor is open (else {@link #text} and {@link #color}). */
		public Supplier<String> live;
		public java.util.function.IntSupplier liveColor;
		/** STRIP: the name whose matches it shows. */
		public El name;
		/** The row it belongs to on the right side (0: none): a row scrolls into and out of view as a whole. */
		int row;

		El(Kind kind, int x, int y, int w, int h) { this.kind = kind; this.x = x; this.y = y; this.w = w; this.h = h; }

		public boolean contains(double px, double py) { return px >= x && px < x + w && py >= y && py < y + h; }

		@Override public String toString() { return kind + " \"" + text + "\" at " + x + "," + y + " " + w + "x" + h; }
	}

	/**
	 * @param cw     width of the editor's column, GUI pixels
	 * @param now    what the block sees (asked again whenever a lamp or reading is drawn)
	 * @param before the snapshot before that one, or null
	 * @param heldBlock what the player holds as a block id, {@code heldItem} as an item id; null if nothing
	 * @param where  "by Ted   10 64 -3", shown after the output at the top
	 * @param you    the side of the block the player stands on, or null
	 * @param frame  a number that changes every time the screen is drawn: which rules hold is worked out once per frame,
	 *               however many lamps and words ask
	 */
	public record Inputs(Draft draft, State state, boolean edit, int cw, int height, Measure m, Supplier<Snapshot> now, Supplier<Snapshot> before,
						 String heldBlock, String heldItem, String where, Side you, java.util.function.LongSupplier frame) {}

	/** {@code x0}: where the right side starts; {@code statusX}, {@code statusY}: where the screen writes its status line. */
	public record Result(List<El> els, int x0, int statusX, int statusY, int maxScroll) {}

	// ---- words for the options ----

	static final List<Action.Kind> OUTPUT_KINDS = List.of(Action.Kind.SET, Action.Kind.PULSE, Action.Kind.TOGGLE, Action.Kind.KEEP);
	private static final List<String> COUNTER_IDS = java.util.stream.IntStream.range(0, Condition.COUNTERS).mapToObj(Condition::counterId).toList();
	private static final List<String> TIMER_IDS = java.util.stream.IntStream.range(0, Condition.TIMERS).mapToObj(Condition::timerId).toList();
	/** The presets offered on a new block's start screen, with a few words each. */
	static final String[][] STARTERS = {{"Clock", "a pulse every second"}, {"Sorter station", "moves items by name"}, {"Latch", "a button flips it"}};

	static String kindNote(Condition.Kind k) {
		return switch (k) {
			case BLOCK -> "what's next to it"; case SIGNAL -> "redstone power coming in"; case ITEMS -> "how many, in a container";
			case PLAYERS -> "anyone nearby"; case CHANGE -> "a signal turns on or off"; case COUNTER -> "A to D, this block's numbers";
			case TIMER -> "T1 to T4, running or done"; case FILL -> "how full, like a comparator";
		};
	}

	static String opNote(Condition.Op op) {
		return switch (op) { case EQ -> "equal to"; case NE -> "not equal to"; case LT -> "less than"; case LE -> "at most"; case GT -> "more than";
			case GE -> "at least"; default -> ""; };
	}

	static String actionNote(Action.Kind k) {
		return switch (k) {
			case SET -> "set the signal strength"; case PULSE -> "on for a moment, then off"; case TOGGLE -> "flip on and off each time";
			case KEEP -> "leave it as it is"; case MOVE -> "items between containers"; case ADD -> "to a counter"; case SUBTRACT -> "from a counter";
			case SET_COUNTER -> "a counter to a number"; case START -> "a timer"; case STOP -> "a timer";
		};
	}

	public static String sideLabel(Side s) { return s == Side.ANY ? "Any side" : s.label(); }

	public static String kindLabel(Condition.Kind k) {
		return switch (k) { case BLOCK -> "Block"; case SIGNAL -> "Signal"; case ITEMS -> "Items"; case PLAYERS -> "Players"; case CHANGE -> "Change";
			case COUNTER -> "Counter"; case TIMER -> "Timer"; case FILL -> "Fill"; };
	}

	public static String actionLabel(Action.Kind k) {
		return switch (k) { case SET -> "Output"; case PULSE -> "Pulse"; case TOGGLE -> "Toggle"; case KEEP -> "Keep"; case MOVE -> "Move"; case ADD -> "Add";
			case SUBTRACT -> "Subtract"; case SET_COUNTER -> "Set"; case START -> "Start"; case STOP -> "Stop"; };
	}

	/** Short text for an extra action in the rule list: "move diamond", "A+1", "A-1", "A=0", "start T1", "stop T1". */
	static String extraSummary(Draft.Extra ex) {
		String id = Condition.memoryLabel(ex.id);
		return switch (ex.kind) {
			case ADD -> id + "+" + ex.amount; case SUBTRACT -> id + "-" + ex.amount; case SET_COUNTER -> id + "=" + ex.amount;
			case START -> "start " + id; case STOP -> "stop " + id; default -> "move " + ex.id;
		};
	}

	/** Short text for the rule list, e.g. "north is red_wool -> 15" or "always: move diamond". */
	public static String summary(Draft.Rule r, boolean output) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < r.conditions.size(); i++) {
			Draft.Cond c = r.conditions.get(i);
			if (i > 0) sb.append(" & ");
			String side = c.side == Side.ANY ? "any" : c.side.label().toLowerCase(Locale.ROOT);
			sb.append(switch (c.kind) {
				case BLOCK -> side + " " + c.op.symbol + " " + c.id;
				case SIGNAL -> "sig " + side + " " + c.op.symbol + c.number;
				case ITEMS -> c.id + " " + c.op.symbol + c.number;
				case PLAYERS -> "players " + c.op.symbol + c.number;
				case CHANGE -> side + (c.turnsOn() ? " on" : " off");
				case COUNTER -> Condition.memoryLabel(c.id) + c.op.symbol + c.number;
				case TIMER -> Condition.memoryLabel(c.id) + (c.op == Condition.Op.IS ? " " : " not ") + (c.running() ? "running" : "done");
				case FILL -> "fill " + side + c.op.symbol + c.number;
			});
		}
		if (r.conditions.isEmpty()) sb.append("always");
		if (output) {
			sb.append(" -> ").append(switch (r.action) {
				case SET -> r.strength; case PULSE -> "pulse " + r.strength; case TOGGLE -> "toggle " + r.strength; case KEEP -> "keep"; default -> "?";
			});
			for (Draft.Extra ex : r.extras) sb.append(", ").append(extraSummary(ex));
		} else {
			sb.append(": ");
			for (int i = 0; i < r.extras.size(); i++) sb.append(i == 0 ? "" : ", ").append(extraSummary(r.extras.get(i)));
		}
		return sb.toString();
	}

	/** {@code text} broken into lines at most {@code w} wide. */
	static List<String> wrap(Measure m, String text, int w) {
		List<String> lines = new ArrayList<>();
		StringBuilder line = new StringBuilder();
		for (String word : text.split(" ")) {
			if (line.length() > 0 && m.width(line + " " + word) > w) { lines.add(line.toString()); line.setLength(0); }
			line.append(line.length() > 0 ? " " : "").append(word);
		}
		if (line.length() > 0) lines.add(line.toString());
		return lines;
	}

	// ---- building ----

	public static Result build(Inputs in) { return new Builder(in).build(); }

	private static final class Builder {
		final Inputs in;
		final Measure m;
		final Draft draft;
		final State state;
		final boolean edit;
		final int cw, height;
		final List<El> fixed = new ArrayList<>(), pane = new ArrayList<>();
		List<El> into = fixed;
		/** Whether a side button has room for the icon of what's on that side. */
		boolean sideIcons;
		/** The row being built on the right side (see {@link El#row}), and how many there have been. */
		int row, rows;

		void nextRow() { row = ++rows; }

		Builder(Inputs in) {
			this.in = in; m = in.m(); draft = in.draft(); state = in.state(); edit = in.edit(); cw = in.cw(); height = in.height();
		}

		Snapshot now() { return in.now().get(); }
		Snapshot before() { return in.before().get(); }

		El add(Kind kind, int x, int y, int w, int h) {
			El el = new El(kind, x, y, w, h);
			el.row = into == pane ? row : 0;
			into.add(el);
			return el;
		}

		El word(int x, int y, String text, int color) {
			El el = add(Kind.WORD, x, y, m.width(text), 9);
			el.text = text;
			el.color = color;
			return el;
		}

		/** A grey word between widgets on a row; returns the x after it. */
		int wordAfter(int x, int y, String text) {
			word(x + 1, y + 5, text, GRAY);
			return x + m.width(text) + 5;
		}

		/** Wrapped text; returns the y below it. */
		int words(int x, int y, int w, String text, int color) {
			for (String line : wrap(m, text, Math.max(40, w))) { word(x, y, line, color); y += 11; }
			return y;
		}

		El button(int x, int y, int w, int h, String text, Runnable press, After after) {
			El el = add(Kind.BUTTON, x, y, w, h);
			el.text = text;
			el.press = press;
			el.after = after;
			return el;
		}

		El lamp(int x, int y, Supplier<Lamp> lamp) {
			El el = add(Kind.LAMP, x, y, 8, 8);
			el.lamp = lamp;
			return el;
		}

		int choiceWidth(List<Option> options, boolean icon) {
			int w = 0;
			for (Option o : options) w = Math.max(w, m.width(o.label()));
			return w + 20 + (icon ? 18 : 0);
		}

		/** A button showing the current option; pressing it lists them all. Returns the x after it. */
		int choice(int x, int y, Supplier<List<Option>> options, int current, IntConsumer choose) {
			choiceEl(x, y, options, current, choose, false);
			return x + choiceWidth(options.get(), false) + 2;
		}

		El choiceEl(int x, int y, Supplier<List<Option>> options, int current, IntConsumer choose, boolean icon) {
			List<Option> all = options.get();
			El el = add(Kind.CHOICE, x, y, choiceWidth(all, icon), 18);
			el.options = options;
			el.current = current;
			el.text = current >= 0 && current < all.size() ? all.get(current).label() : "";
			el.choose = choose;
			el.active = edit;
			el.after = After.CHANGED;
			return el;
		}

		<T> Supplier<List<Option>> plain(List<T> values, java.util.function.Function<T, String> label, java.util.function.Function<T, String> note) {
			List<Option> options = values.stream().map(v -> new Option(label.apply(v), note.apply(v))).toList();
			return () -> options;
		}

		int sideWidth(List<Side> sides) {
			int w = 0;
			for (Side s : sides) w = Math.max(w, m.width(sideLabel(s)));
			return w + 20 + (sideIcons ? 18 : 0);
		}

		/** The button for a side: opens the map of the block's sides, each with what's there. {@code signals}: say their strength too. */
		int side(int x, int y, List<Side> sides, Side current, Consumer<Side> set, boolean signals) {
			Supplier<List<Option>> options = () -> sides.stream().map(s -> {
				if (s == Side.ANY) return new Option(sideLabel(s), "whichever has it");
				Snapshot now = now();
				int signal = now.signal(s);
				return new Option(sideLabel(s), now.block(s) + (signals && signal > 0 ? ", signal " + signal : ""), now.block(s), true);
			}).toList();
			El el = choiceEl(x, y, options, sides.indexOf(current), i -> set.accept(sides.get(i)), sideIcons);
			el.map = true;
			el.sides = sides;
			if (sideIcons) el.icon = () -> current == Side.ANY ? null : now().block(current);
			return x + el.w + 2;
		}

		int ops(int x, int y, Draft.Cond cond) {
			List<Condition.Op> ops = cond.ops();
			return choice(x, y, plain(ops, o -> o.symbol, EditorLayout::opNote), ops.indexOf(cond.op), i -> cond.op = ops.get(i));
		}

		int opsWidth(Draft.Cond cond) { return choiceWidth(plain(cond.ops(), o -> o.symbol, EditorLayout::opNote).get(), false); }

		int counter(int x, int y, String id, Consumer<String> set) {
			Supplier<List<Option>> options = () -> COUNTER_IDS.stream().map(c -> new Option(Condition.memoryLabel(c), "= " + now().counter(Condition.counterIndex(c)))).toList();
			return choice(x, y, options, COUNTER_IDS.indexOf(id), i -> set.accept(COUNTER_IDS.get(i)));
		}

		int timer(int x, int y, String id, Consumer<String> set) {
			Supplier<List<Option>> options = () -> TIMER_IDS.stream().map(t -> new Option(Condition.memoryLabel(t), Readings.timer(now(), Condition.timerIndex(t)))).toList();
			return choice(x, y, options, TIMER_IDS.indexOf(id), i -> set.accept(TIMER_IDS.get(i)));
		}

		int box(Kind kind, int x, int y, int w, String value, Consumer<String> set) {
			El el = add(kind, x, y, w, 18);
			el.value = value;
			el.set = set;
			el.active = edit;
			return x + w + 2;
		}

		int number(int x, int y, int w, String value, Consumer<String> set) { return box(Kind.NUMBER, x, y, w, value, set); }

		/**
		 * A block or item name, with under it the icons of what it matches and, when the rules can be edited, the
		 * shortcuts: a pattern or the one thing that's there, and what's in the player's hand.
		 */
		int name(int x, int y, int w, String value, Consumer<String> set, boolean block, Side side, String single) {
			El name = add(Kind.NAME, x, y, w, 18);
			name.value = value;
			name.set = set;
			name.block = block;
			name.side = side;
			name.active = edit;
			// The shortcuts go right of the icons; on a narrow screen they take room from the icons, then from their own text.
			NamePicker.Offer offer = edit ? NamePicker.offer(Readings.here(block, side, now()), value, block) : null;
			String held = block ? in.heldBlock() : in.heldItem();
			boolean hand = edit && held != null && !held.equals(value);
			// "use stone" only when the name doesn't already take what's there.
			if (single != null && !value.isEmpty() && dev.whizzle.logic.model.IdPattern.valid(value) && dev.whizzle.logic.model.IdPattern.matches(value, single, block)) single = null;
			boolean use = edit && (offer != null || single != null);
			String sub = offer != null ? " " + offer.kinds() + " kinds" : "";
			String only = single;
			String text = !use ? "" : offer != null ? m.fit(offer.pattern(), 90) : m.fit("use " + only, 96);
			int handW = hand ? m.width("hand") + 8 : 0, right = cw - 6;
			int total = (use ? m.width(text + sub) + 8 : 0) + (use && hand ? 2 : 0) + handW;
			int bx = x + STRIP_W + 2;
			if (bx + total > right) bx = Math.max(x + 40, right - total);
			if (use && bx + total > right) {
				text = m.fit(text, Math.max(12, right - bx - 8 - m.width(sub) - (hand ? 2 + handW : 0)));
				total = m.width(text + sub) + 8 + (hand ? 2 + handW : 0);
			}
			El strip = add(Kind.STRIP, x, y + 20, (total > 0 ? bx - 2 : Math.min(x + STRIP_W, right)) - x, 16);
			strip.name = name;
			strip.block = block;
			if (use) {
				String to = offer != null ? offer.pattern() : only;
				El button = button(bx, y + 21, m.width(text + sub) + 8, 14, text, () -> set.accept(to), After.CHANGED);
				button.sub = sub;
				if (offer != null) button.color = PATTERN;
				bx += button.w + 2;
			}
			if (hand) button(bx, y + 21, handW, 14, "hand", () -> set.accept(held), After.CHANGED);
			return x + w + 2;
		}

		Result build() {
			int listW = Math.clamp(cw / 4, 112, 180), x0 = listW + 14;
			sideIcons = cw - x0 >= 400;
			state.selected = Math.min(state.selected, Math.max(0, draft.rules.size() - 1));
			state.selectedAlso = Math.min(state.selectedAlso, Math.max(0, draft.also.size() - 1));
			boolean also = state.alsoTab;
			List<Draft.Rule> list = also ? draft.also : draft.rules;
			int sel = also ? state.selectedAlso : state.selected;

			// Top bar
			word(6, 9, "Logic Block", AQUA);
			El info = add(Kind.WORD, 76, 9, Math.max(0, cw - 76 - 142), 9);
			info.color = GRAY;
			info.live = () -> "Output " + now().output() + "   " + in.where();
			El menu = add(Kind.MENU, cw - 134, 4, 20, 18);
			menu.text = "Menu";
			List<Option> items = List.of(new Option("Copy", "these rules, to paste or share"), new Option("Paste", "rules you copied", null, edit),
				new Option("Presets", "ready-made rules", null, edit), new Option("Labels", "the letters on the block's sides"));
			menu.options = () -> items;
			menu.commands = List.of(Command.COPY, Command.PASTE, Command.PRESETS, Command.LABELS);
			El save = button(cw - 110, 4, 50, 18, "Save", null, After.NOTHING);
			save.command = Command.SAVE;
			save.active = edit;
			button(cw - 56, 4, 50, 18, "Close", null, After.NOTHING).command = Command.CLOSE;

			// Tabs, and a line saying how the open one works
			int half = (listW - 2) / 2;
			button(6, 26, half, 16, "If/Else (" + draft.rules.size() + ")", () -> { state.alsoTab = false; state.scroll = 0; }, After.REBUILD).color = also ? OFF_TAB : WHITE;
			button(6 + half + 2, 26, half, 16, "When (" + draft.also.size() + ")", () -> { state.alsoTab = true; state.scroll = 0; }, After.REBUILD).color = also ? WHITE : OFF_TAB;
			int y = 45;
			for (String line : wrap(m, also ? "every true rule runs" : "first true rule sets the output", listW)) { word(6, y, line, DIM); y += 10; }
			y += 3;

			// The tab's rules; on a low screen with many of them the rows get shorter, so the list still ends above "Otherwise".
			int room = (also ? height - 4 : height - 28) - 22 - y;
			int pitch = list.isEmpty() ? 20 : Math.clamp(room / list.size(), 13, 20);
			for (int i = 0; i < list.size(); i++) {
				int index = i;
				Draft.Rule rule = list.get(i);
				button(6, y, listW, pitch - 2, m.fit((i + 1) + ". " + summary(rule, !also), listW - 30),
					() -> { if (also) state.selectedAlso = index; else state.selected = index; state.scroll = 0; }, After.REBUILD);
				lamp(9, y + (pitch - 2 - 8) / 2, () -> ruleLamp(rule, index, also));
				if (i == sel) add(Kind.FRAME, 5, y - 1, listW + 2, pitch).color = WHITE;
				y += pitch;
			}
			if (list.isEmpty()) y += 4;
			y += 2;
			boolean has = !list.isEmpty();
			button(6, y, 46, 18, also ? "+ When" : "+ Rule", () -> {
				list.add(also ? Draft.Rule.also() : new Draft.Rule());
				if (also) state.selectedAlso = list.size() - 1; else state.selected = list.size() - 1;
				state.scroll = 0;
			}, After.CHANGED).active = edit && list.size() < (also ? Program.MAX_ALSO : Program.MAX_RULES);
			button(54, y, 18, 18, "↑", () -> moveRule(list, sel, -1), After.CHANGED).active = edit && has && sel > 0;
			button(74, y, 18, 18, "↓", () -> moveRule(list, sel, 1), After.CHANGED).active = edit && has && sel < list.size() - 1;
			button(94, y, 26, 18, "Del", () -> {
				list.remove(sel);
				if (also) state.selectedAlso = Math.max(0, sel - 1); else state.selected = Math.max(0, sel - 1);
				state.scroll = 0;
			}, After.CHANGED).active = edit && has;

			// Otherwise (part of the If/Else rules), and where the status line goes
			int statusX = Math.max(x0, 140), oy = height - 24;
			if (!also) {
				word(6, oy + 5, "Otherwise", WHITE);
				int x = 6 + m.width("Otherwise") + 6;
				List<Option> keep = List.of(new Option("Output", actionNote(Action.Kind.SET)), new Option("Keep", actionNote(Action.Kind.KEEP)));
				x = choice(x, oy, () -> keep, draft.otherwiseKeep ? 1 : 0, i -> draft.otherwiseKeep = i == 1);
				if (!draft.otherwiseKeep) x = number(x, oy, 22, draft.otherwiseStrength, v -> draft.otherwiseStrength = v);
				statusX = Math.max(statusX, x + 8);
			}

			// The right side
			into = pane;
			if (has) ruleEditor(list.get(sel), sel, !also, x0);
			else if (edit && draft.rules.isEmpty() && draft.also.isEmpty()) startScreen(x0);
			else words(x0, 30, cw - x0 - 8, also
				? "When rules: every true one runs, alongside If/Else. For moving items, counters and timers. \"+ When\" adds one."
				: "If/Else rules: the first true one sets the output, \"Otherwise\" covers the rest. \"+ Rule\" adds one.", GRAY);

			// It scrolls when it's taller than the screen; what isn't wholly in view is left out.
			int top = 28, bottom = height - 28, content = 0;
			for (El el : pane) content = Math.max(content, el.y + el.h);
			boolean overflow = content > bottom;
			if (overflow) bottom = height - 40;
			int maxScroll = Math.max(0, content - bottom);
			state.scroll = Math.clamp(state.scroll, 0, maxScroll);
			List<El> all = new ArrayList<>(fixed);
			java.util.Set<Integer> cut = new java.util.HashSet<>();
			for (El el : pane) {
				el.y -= state.scroll;
				if (el.y < top || el.y + el.h > bottom) cut.add(el.row);
			}
			for (El el : pane) if (el.row == 0 ? el.y >= top && el.y + el.h <= bottom : !cut.contains(el.row)) all.add(el);
			if (overflow) {
				into = all;
				String hint = state.scroll < maxScroll ? "scroll for the rest" : "scroll back up";
				word(cw - 6 - m.width(hint), height - 38, hint, DIM);
			}
			return new Result(all, x0, statusX, height - 19, maxScroll);
		}

		/** Which rules hold, for the frame they were worked out in. */
		long heldFrame = Long.MIN_VALUE;
		boolean[] rulesHold = new boolean[0], alsoHold = new boolean[0];
		int applies = -1;

		void whichHold() {
			long frame = in.frame().getAsLong();
			if (frame == heldFrame && rulesHold.length == draft.rules.size() && alsoHold.length == draft.also.size()) return;
			heldFrame = frame;
			rulesHold = new boolean[draft.rules.size()];
			alsoHold = new boolean[draft.also.size()];
			applies = -1;
			for (int i = 0; i < rulesHold.length; i++) {
				rulesHold[i] = Readings.allTrue(draft.rules.get(i), now(), before());
				if (rulesHold[i] && applies < 0) applies = i;
			}
			for (int i = 0; i < alsoHold.length; i++) alsoHold[i] = Readings.allTrue(draft.also.get(i), now(), before());
		}

		Lamp ruleLamp(Draft.Rule rule, int index, boolean also) {
			whichHold();
			boolean[] hold = also ? alsoHold : rulesHold;
			boolean holds = index < hold.length && hold[index];
			if (also) return new Lamp(holds ? 1 : 0, holds ? "true: runs now" : "not true now");
			boolean applies = this.applies == index;
			return new Lamp(applies ? 1 : 0, applies ? "true: sets the output now" : holds ? "true, but an earlier rule comes first" : "not true now");
		}

		static void moveRule(List<Draft.Rule> list, int sel, int d) { list.add(sel + d, list.remove(sel)); }

		void ruleEditor(Draft.Rule rule, int index, boolean output, int x0) {
			int y = 30;
			nextRow();
			lamp(x0, y, () -> ruleLamp(rule, index, !output));
			El title = add(Kind.WORD, x0 + 12, y, cw - x0 - 18, 9);
			title.live = () -> {
				boolean on = ruleLamp(rule, index, !output).state() == 1;
				return (output ? "Rule " : "When rule ") + (index + 1) + (on ? (output ? "  (true right now)" : "  (true: runs now)") : "")
					+ (!output && rule.conditions.isEmpty() ? "  (no conditions: always)" : "");
			};
			title.liveColor = () -> ruleLamp(rule, index, !output).state() == 1 ? GREEN : WHITE;
			y = 44;
			List<Condition.Kind> kinds = List.of(Condition.Kind.values());
			for (int c = 0; c < rule.conditions.size(); c++) {
				Draft.Cond cond = rule.conditions.get(c);
				nextRow();
				lamp(x0, y + 5, () -> {
					Readings.Check k = Readings.check(cond, now(), before());
					return new Lamp(k.ok() ? 1 : 0, "now: " + k.reading());
				});
				int x = x0 + 12;
				x = choice(x, y, plain(kinds, EditorLayout::kindLabel, EditorLayout::kindNote), kinds.indexOf(cond.kind), i -> cond.setKind(kinds.get(i)));
				boolean named = false;
				switch (cond.kind) {
					case BLOCK -> {
						x = side(x, y, cond.sides(), cond.side, s -> cond.side = s, false);
						x = ops(x, y, cond);
						String seen = cond.side == Side.ANY ? "" : now().block(cond.side);
						x = name(x, y, Math.clamp(cw - 6 - x - 20, 70, 130), cond.id, v -> cond.id = v, true, cond.side, seen.isEmpty() || seen.equals(cond.id) ? null : seen);
						named = true;
					}
					case SIGNAL -> {
						x = side(x, y, cond.sides(), cond.side, s -> cond.side = s, true);
						x = ops(x, y, cond);
						x = number(x, y, 24, cond.number, v -> cond.number = v);
					}
					case ITEMS -> {
						x = side(x, y, cond.sides(), cond.side, s -> cond.side = s, false);
						x = name(x, y, Math.clamp(cw - 6 - x - (opsWidth(cond) + 2 + 36 + 20), 70, 130), cond.id, v -> cond.id = v, false, cond.side,
							Readings.mostIn(cond.side, cond.id, now()));
						x = ops(x, y, cond);
						x = number(x, y, 34, cond.number, v -> cond.number = v);
						named = true;
					}
					case PLAYERS -> {
						x = wordAfter(x, y, "within");
						x = number(x, y, 22, cond.radius, v -> cond.radius = v);
						x = ops(x, y, cond);
						x = number(x, y, 24, cond.number, v -> cond.number = v);
					}
					case CHANGE -> {
						x = side(x, y, cond.sides(), cond.side, s -> cond.side = s, true);
						List<Option> turns = List.of(new Option("turns on", "the moment power arrives"), new Option("turns off", "the moment it goes"));
						x = choice(x, y, () -> turns, cond.turnsOn() ? 0 : 1, i -> { if ((i == 0) != cond.turnsOn()) cond.flipTurn(); });
					}
					case COUNTER -> {
						x = counter(x, y, cond.id, v -> cond.id = v);
						x = ops(x, y, cond);
						x = number(x, y, 40, cond.number, v -> cond.number = v);
					}
					case FILL -> {
						x = side(x, y, cond.sides(), cond.side, s -> cond.side = s, false);
						x = ops(x, y, cond);
						x = number(x, y, 24, cond.number, v -> cond.number = v);
						x = wordAfter(x, y, "of 15");
					}
					case TIMER -> {
						x = timer(x, y, cond.id, v -> cond.id = v);
						x = ops(x, y, cond);
						List<Option> states = List.of(new Option("running", "started and still counting"), new Option("done", "ran out, until it's started again"));
						x = choice(x, y, () -> states, cond.running() ? 0 : 1, i -> { if ((i == 0) != cond.running()) cond.flipTimer(); });
					}
				}
				int at = c;
				button(x + 2, y, 16, 18, "x", () -> rule.conditions.remove(at), After.CHANGED).active = edit;
				y += named ? NAME_ROW : LINE;
			}
			nextRow();
			button(x0, y, 70, 18, "+ Condition", () -> rule.conditions.add(new Draft.Cond()), After.CHANGED).active = edit && rule.conditions.size() < Program.MAX_CONDITIONS;
			y += 24;
			if (output) {
				nextRow();
				word(x0, y + 5, "Then", WHITE);
				int x = x0 + 34;
				x = choice(x, y, plain(OUTPUT_KINDS, EditorLayout::actionLabel, EditorLayout::actionNote), OUTPUT_KINDS.indexOf(rule.action), i -> rule.action = OUTPUT_KINDS.get(i));
				if (rule.action != Action.Kind.KEEP) x = number(x, y, 22, rule.strength, v -> rule.strength = v);
				if (rule.action == Action.Kind.PULSE) {
					x = wordAfter(x, y, "for");
					x = number(x, y, 34, rule.ticks, v -> rule.ticks = v);
					wordAfter(x, y, "ticks (20 = 1 s)");
				}
				y += 24;
			}
			for (int e = 0; e < rule.extras.size(); e++) {
				Draft.Extra ex = rule.extras.get(e);
				boolean move = ex.kind == Action.Kind.MOVE;
				nextRow();
				lamp(x0, y + 5, () -> new Lamp(ex.kind == Action.Kind.MOVE ? (Readings.moveReady(ex, now()) ? 1 : 0) : 2, "now: " + Readings.extra(ex, now())));
				word(x0 + 12, y + 5, !output && e == 0 ? "Do" : "and", WHITE);
				int x = x0 + 34;
				x = choice(x, y, plain(Draft.EXTRA_KINDS, EditorLayout::actionLabel, EditorLayout::actionNote), Draft.EXTRA_KINDS.indexOf(ex.kind), i -> ex.setKind(Draft.EXTRA_KINDS.get(i)));
				switch (ex.kind) {
					case ADD, SUBTRACT -> {
						x = number(x, y, 40, ex.amount, v -> ex.amount = v);
						x = wordAfter(x, y, ex.kind == Action.Kind.ADD ? "to" : "from");
						x = counter(x, y, ex.id, v -> ex.id = v);
					}
					case SET_COUNTER -> {
						x = counter(x, y, ex.id, v -> ex.id = v);
						x = wordAfter(x, y, "to");
						x = number(x, y, 40, ex.amount, v -> ex.amount = v);
					}
					case START -> {
						x = timer(x, y, ex.id, v -> ex.id = v);
						x = wordAfter(x, y, "for");
						x = box(Kind.DECIMAL, x, y, 40, ex.seconds, v -> ex.seconds = v);
						x = wordAfter(x, y, "seconds");
					}
					case STOP -> x = timer(x, y, ex.id, v -> ex.id = v);
					default -> {
						List<Side> faces = List.of(Side.FACES);
						int rest = m.width("from") + 5 + sideWidth(faces) + 2 + m.width("to") + 5 + sideWidth(faces) + 2 + 20;
						x = name(x, y, Math.clamp(cw - 6 - x - rest, 60, 130), ex.id, v -> ex.id = v, false, ex.from, Readings.mostIn(ex.from, ex.id, now()));
						x = wordAfter(x, y, "from");
						x = side(x, y, faces, ex.from, s -> ex.from = s, false);
						x = wordAfter(x, y, "to");
						x = side(x, y, faces, ex.to, s -> ex.to = s, false);
					}
				}
				int at = e;
				button(x + 2, y, 16, 18, "x", () -> rule.extras.remove(at), After.CHANGED).active = edit;
				y += move ? NAME_ROW : LINE;
			}
			nextRow();
			button(x0, y, 60, 18, "+ Action", () -> rule.extras.add(new Draft.Extra()), After.CHANGED).active = edit && rule.extras.size() < Draft.Rule.maxExtras(output);
			y += 24;
			row = 0;   // the notes below come and go line by line
			int w = cw - x0 - 6;
			if (rule.extras.stream().anyMatch(ex -> ex.kind == Action.Kind.MOVE))
				y = words(x0, y, w, "Moves one item every 8 ticks (hopper speed), between containers touching this block, every check while the rule is true.", GRAY);
			if (rule.extras.stream().anyMatch(ex -> ex.kind != Action.Kind.MOVE))
				y = words(x0, y, w, "Counter and timer actions run once each time the rule becomes true. Counters and timers belong to this block; saving starts them over.", GRAY);
			for (int i = 0; i < 2; i++) {
				int line = i;
				El warn = add(Kind.WORD, x0, y + i * 11, w, 9);
				warn.color = GOLD;
				warn.live = () -> {
					String s = Readings.dustWarning(rule, now());
					List<String> lines = s == null ? List.of() : wrap(m, s, w);
					return line < lines.size() ? lines.get(line) : "";
				};
			}
		}

		/** A new block with no rules yet: a few ready-made starts, or an empty rule. */
		void startScreen(int x0) {
			word(x0, 30, "What should this block do?", WHITE);
			List<Presets.Preset> presets = Presets.all();
			int cardW = Math.clamp((cw - x0 - 6 - 8) / 3, 70, 130), x = x0;
			for (String[] starter : STARTERS) {
				for (int i = 0; i < presets.size(); i++) {
					if (!presets.get(i).name().equals(starter[0])) continue;
					El card = add(Kind.CARD, x, 44, cardW, 32);
					card.text = starter[0];
					card.sub = starter[1];
					card.preset = i;
					x += cardW + 4;
				}
			}
			button(x0, 82, 84, 18, "+ Start empty", () -> { draft.rules.add(new Draft.Rule()); state.alsoTab = false; state.selected = 0; }, After.CHANGED);
			button(x0 + 88, 82, 70, 18, "All presets", null, After.NOTHING).command = Command.PRESETS;
			words(x0, 108, cw - x0 - 8, "A preset fills in the editor. Nothing changes on the block until you Save.", GRAY);
		}
	}
}
