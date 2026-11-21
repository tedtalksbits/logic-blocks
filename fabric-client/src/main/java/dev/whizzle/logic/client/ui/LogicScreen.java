package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.client.net.Messages;
import dev.whizzle.logic.client.net.Net;
import dev.whizzle.logic.model.*;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The rule editor. Left: two tabs, the <b>If/Else</b> rules (the first true one sets the output; the one matching right
 * now is green) with "otherwise", and the <b>When</b> rules (every true one runs). Right: the selected rule's conditions, each
 * with what the block sees right now and true/false, then what it does: an output, then moves and counter and timer actions. Edits are checked live
 * against what the block sees, before saving.
 */
public final class LogicScreen extends Screen {
	static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFB8C0CC, GREEN = 0xFF6EE07A, RED = 0xFFFF6B6B, GOLD = 0xFFFFC857, AQUA = 0xFF6FD3FF, PANEL = 0x90101418;
	private static final int ROW = 36;
	/** The editor is laid out in a centred column at most this wide (GUI pixels), like a page with a max width. */
	static final int MAX_WIDTH = 1080;
	private int listW = 112;
	/** Content width and left edge of the centred column. */
	private int cw, left;
	private final java.util.Map<EditBox, IdCompletion> idBoxes = new java.util.HashMap<>();
	/** A block or item name box, and the side whose block or container its name is checked against. */
	private record NameBox(EditBox box, boolean block, boolean edit, Side side) {}
	private final List<NameBox> nameBoxes = new java.util.ArrayList<>();
	/**
	 * The list under the name being typed ({@link NamePicker}): the box it belongs to, the text and surroundings it was
	 * worked out for, the row chosen with the arrow keys (-1: none), and whether Esc put it away.
	 */
	private NameBox pickerName;
	private EditBox pickerBox;
	private String pickerText;
	private java.util.Map<String, Integer> pickerHere;
	private NamePicker.View pickerView;
	private NamePanel.Layout pickerLayout;
	private int pickIndex = -1;
	private boolean pickerClosed;
	/**
	 * The panel showing what a pattern or tag matches (pointer on a name, or on a tag in the list): what it's for, which
	 * of the matches the scroll wheel has chosen, and the matches while it's on screen (else null).
	 */
	private String matchesKey;
	private int matchesChosen;
	private NamePicker.Summary matchesShown;
	/** Whose matches those are: the name box, and for a row of the list the tag or pattern that row would put in. */
	private NameBox matchesBox;
	private String matchesRow;
	private boolean matchesCanLeaveOut;
	private int pickerCursor = -1;
	/** Words between the buttons of the extra-action rows ("from", "to", "for"), placed by init, drawn each frame. */
	private record Label(int x, int y, String text, int color) {}
	private final List<Label> labels = new java.util.ArrayList<>();
	/** Where each extra action's "now:" line starts. */
	private final List<Integer> extraNowX = new java.util.ArrayList<>();
	/** Where each condition's "now:" line starts: after its use/hand buttons, as placed by init. */
	private final List<Integer> condNowX = new java.util.ArrayList<>();
	private static IdCompletion blockIds, itemIds;

	private final Messages.Open open;
	private Draft draft;
	private Snapshot snapshot, previous;
	/** Which list is shown on the left, and the selected rule in each. */
	private boolean alsoTab;
	private int selected, selectedAlso;
	private boolean dirty, closing;
	private String status = "";
	private int statusColor = GRAY;

	public LogicScreen(Messages.Open open) {
		super(Component.literal("Logic Block"));
		this.open = open;
		this.draft = new Draft(open.program());
		this.snapshot = open.snapshot();
		NamePicker.clearCache();   // tags may have changed since the editor was last open
		// A block with only "also" rules (a sorter station, say) opens on them.
		this.alsoTab = draft.rules.isEmpty() && !draft.also.isEmpty();
		if (!open.editable()) { status = "Only " + open.owner() + " or an operator can change this Logic Block."; statusColor = GOLD; }
	}

	// ---- server events ----

	public void onLive(Messages.Live live) {
		if (live.x() == open.x() && live.y() == open.y() && live.z() == open.z()) { previous = snapshot; snapshot = live.snapshot(); }
	}

	public void onResult(Messages.Result r) {
		status = r.message();
		statusColor = r.ok() ? GREEN : RED;
		if (r.ok()) dirty = false;
	}

	// ---- layout ----

	private int rightX() { return listW + 14; }
	private List<Draft.Rule> list() { return alsoTab ? draft.also : draft.rules; }
	private int sel() { return alsoTab ? selectedAlso : selected; }
	private void select(int i) { if (alsoTab) selectedAlso = i; else selected = i; }

	@Override
	protected void init() {
		boolean edit = open.editable();
		cw = Math.min(width, MAX_WIDTH);
		left = (width - cw) / 2;
		idBoxes.clear();
		nameBoxes.clear();
		labels.clear();
		extraNowX.clear();
		condNowX.clear();
		listW = Math.clamp(cw / 4, 112, 180);
		selected = Math.min(selected, Math.max(0, draft.rules.size() - 1));
		selectedAlso = Math.min(selectedAlso, Math.max(0, draft.also.size() - 1));

		// Top bar
		Button save = addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).pos(cw - 110, 4).size(50, 18).build());
		save.active = edit;
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).pos(cw - 56, 4).size(50, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Labels"), b -> dev.whizzle.logic.client.LogicClient.openSettings(this))
			.pos(cw - 164, 4).size(50, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Presets"), b -> minecraft.gui.setScreen(new PresetScreen(this)))
			.pos(cw - 216, 4).size(48, 18).build()).active = edit;
		addRenderableWidget(Button.builder(Component.literal("Paste"), b -> paste()).pos(cw - 256, 4).size(38, 18).build()).active = edit;
		addRenderableWidget(Button.builder(Component.literal("Copy"), b -> copy()).pos(cw - 294, 4).size(36, 18).build());

		// Tabs: Output | Also
		int half = (listW - 2) / 2;
		addRenderableWidget(Button.builder(tabText("If/Else", draft.rules.size(), !alsoTab), b -> { alsoTab = false; rebuildWidgets(); })
			.pos(6, 26).size(half, 16).build());
		addRenderableWidget(Button.builder(tabText("When", draft.also.size(), alsoTab), b -> { alsoTab = true; rebuildWidgets(); })
			.pos(6 + half + 2, 26).size(half, 16).build());

		// Rule list for the tab
		List<Draft.Rule> list = list();
		int y = 46;
		int matched = draftMatched();
		for (int i = 0; i < list.size(); i++) {
			int index = i;
			String label = (i == sel() ? "> " : "") + (i + 1) + ". " + summary(list.get(i), !alsoTab);
			Component text = Component.literal(font.plainSubstrByWidth(label, listW - 8));
			if (alsoTab ? allTrue(list.get(i)) : i == matched) text = text.copy().withStyle(ChatFormatting.GREEN);
			addRenderableWidget(Button.builder(text, b -> { select(index); rebuildWidgets(); }).pos(6, y).size(listW, 18).build());
			y += 20;
		}
		if (list.isEmpty()) y += 4;
		int cy = Math.max(y + 2, 46);
		int max = alsoTab ? Program.MAX_ALSO : Program.MAX_RULES;
		Button add = addRenderableWidget(Button.builder(Component.literal(alsoTab ? "+ When" : "+ Rule"), b -> {
			list.add(alsoTab ? Draft.Rule.also() : new Draft.Rule()); select(list.size() - 1); changed();
		}).pos(6, cy).size(46, 18).build());
		add.active = edit && list.size() < max;
		boolean has = !list.isEmpty();
		addRenderableWidget(Button.builder(Component.literal("↑"), b -> moveRule(-1)).pos(54, cy).size(18, 18).build()).active = edit && has && sel() > 0;
		addRenderableWidget(Button.builder(Component.literal("↓"), b -> moveRule(1)).pos(74, cy).size(18, 18).build()).active = edit && has && sel() < list.size() - 1;
		addRenderableWidget(Button.builder(Component.literal("Del"), b -> { list.remove(sel()); select(Math.max(0, sel() - 1)); changed(); })
			.pos(94, cy).size(26, 18).build()).active = edit && has;

		// Otherwise (part of the output rules)
		if (!alsoTab) {
			int oy = height - 24;
			addRenderableWidget(Button.builder(Component.literal(draft.otherwiseKeep ? "Keep" : "Output"), b -> { draft.otherwiseKeep = !draft.otherwiseKeep; changed(); })
				.pos(6 + 58, oy).size(44, 18).build()).active = edit;
			if (!draft.otherwiseKeep) number(6 + 106, oy, 22, draft.otherwiseStrength, v -> draft.otherwiseStrength = v, edit);
		}

		// Selected rule
		if (has) ruleEditor(list.get(sel()), !alsoTab, edit);

		// Everything above was placed as if the screen were cw wide; move it into the centred column.
		if (left > 0) for (var child : children()) if (child instanceof net.minecraft.client.gui.components.AbstractWidget w) w.setX(w.getX() + left);
	}

	private Component tabText(String name, int n, boolean on) {
		// The open tab in white, the other grey; both stay clickable, so neither looks disabled.
		return Component.literal(name + " (" + n + ")").withStyle(on ? ChatFormatting.WHITE : ChatFormatting.GRAY);
	}

	private void ruleEditor(Draft.Rule rule, boolean output, boolean edit) {
		int x0 = rightX(), y = 42;
		for (int c = 0; c < rule.conditions.size(); c++) {
			Draft.Cond cond = rule.conditions.get(c);
			int x = x0;
			x = cycle(x, y, 48, kindLabel(cond.kind), edit, () -> cond.setKind(Draft.next(List.of(Condition.Kind.values()), cond.kind)));
			switch (cond.kind) {
				case BLOCK -> {
					x = cycle(x, y, 44, cond.side.label(), edit, () -> cond.side = Draft.next(cond.sides(), cond.side));
					x = cycle(x, y, 40, cond.op.symbol, edit, () -> cond.op = Draft.next(cond.ops(), cond.op));
					x = text(x, y, 110, cond.id, v -> cond.id = v, edit, true, cond.side);
				}
				case SIGNAL -> {
					x = cycle(x, y, 44, sideLabel(cond.side), edit, () -> cond.side = Draft.next(cond.sides(), cond.side));
					x = cycle(x, y, 22, cond.op.symbol, edit, () -> cond.op = Draft.next(cond.ops(), cond.op));
					x = number(x, y, 24, cond.number, v -> cond.number = v, edit);
				}
				case ITEMS -> {
					x = cycle(x, y, 44, cond.side.label(), edit, () -> cond.side = Draft.next(cond.sides(), cond.side));
					x = text(x, y, 110, cond.id, v -> cond.id = v, edit, false, cond.side);
					x = cycle(x, y, 22, cond.op.symbol, edit, () -> cond.op = Draft.next(cond.ops(), cond.op));
					x = number(x, y, 34, cond.number, v -> cond.number = v, edit);
				}
				case PLAYERS -> {
					x += 36;   // "within" label
					x = number(x, y, 22, cond.radius, v -> cond.radius = v, edit);
					x = cycle(x, y, 22, cond.op.symbol, edit, () -> cond.op = Draft.next(cond.ops(), cond.op));
					x = number(x, y, 24, cond.number, v -> cond.number = v, edit);
				}
				case CHANGE -> {
					x = cycle(x, y, 44, sideLabel(cond.side), edit, () -> cond.side = Draft.next(cond.sides(), cond.side));
					x = cycle(x, y, 56, cond.turnsOn() ? "turns on" : "turns off", edit, cond::flipTurn);
				}
				case COUNTER -> {
					x = cycle(x, y, 22, Condition.memoryLabel(cond.id), edit, () -> cond.id = Draft.next(COUNTER_IDS, cond.id));
					x = cycle(x, y, 22, cond.op.symbol, edit, () -> cond.op = Draft.next(cond.ops(), cond.op));
					x = number(x, y, 40, cond.number, v -> cond.number = v, edit);
				}
				case FILL -> {
						x = cycle(x, y, 44, cond.side.label(), edit, () -> cond.side = Draft.next(cond.sides(), cond.side));
						x = cycle(x, y, 22, cond.op.symbol, edit, () -> cond.op = Draft.next(cond.ops(), cond.op));
						x = number(x, y, 24, cond.number, v -> cond.number = v, edit);
						x = word(x, y, "of 15, like a comparator");
					}
					case TIMER -> {
						x = cycle(x, y, 26, Condition.memoryLabel(cond.id), edit, () -> cond.id = Draft.next(TIMER_IDS, cond.id));
					x = cycle(x, y, 40, cond.op.symbol, edit, () -> cond.op = Draft.next(cond.ops(), cond.op));
					x = cycle(x, y, 50, cond.running() ? "running" : "done", edit, cond::flipTimer);
				}
			}
			int index = c;
			addRenderableWidget(Button.builder(Component.literal("x"), b -> { rule.conditions.remove(index); changed(); }).pos(x + 2, y).size(16, 18).build()).active = edit;
			int nowX = x0 + 50;
			if (edit && (cond.kind == Condition.Kind.BLOCK || cond.kind == Condition.Kind.ITEMS)) {
				int bx = x0 + 48;
				boolean block = cond.kind == Condition.Kind.BLOCK;
				bx = offerButton(bx, y + 20, cond.id, here(block, cond.side), block, suggestion(cond), v -> cond.id = v);
				if (handButton(bx, y + 20, cond.id, block, v -> cond.id = v)) bx += 32;
				nowX = Math.max(nowX, bx + 2);
			}
			condNowX.add(nowX);
			y += ROW;
		}
		Button addCond = addRenderableWidget(Button.builder(Component.literal("+ Condition"), b -> { rule.conditions.add(new Draft.Cond()); changed(); })
			.pos(x0, y).size(70, 18).build());
		addCond.active = edit && rule.conditions.size() < Program.MAX_CONDITIONS;
		y += 24;
		if (output) {
			int x = x0 + 34;   // "Then" label
			x = cycle(x, y, 52, actionLabel(rule.action), edit, () -> rule.action = Draft.next(OUTPUT_KINDS, rule.action));
			if (rule.action != Action.Kind.KEEP) x = number(x, y, 22, rule.strength, v -> rule.strength = v, edit);
			if (rule.action == Action.Kind.PULSE) { x += 22; number(x, y, 34, rule.ticks, v -> rule.ticks = v, edit); }
			y += 24;
		}
		for (int m = 0; m < rule.extras.size(); m++) {
			Draft.Extra ex = rule.extras.get(m);
			labels.add(new Label(x0, y + 5, !output && m == 0 ? "Do" : "and", WHITE));
			int x = x0 + 34;
			x = cycle(x, y, 52, extraLabel(ex.kind), edit, () -> ex.setKind(Draft.next(Draft.EXTRA_KINDS, ex.kind)));
			switch (ex.kind) {
				case ADD, SUBTRACT -> {
					x = number(x, y, 40, ex.amount, v -> ex.amount = v, edit);
					x = word(x, y, ex.kind == Action.Kind.ADD ? "to" : "from");
					x = cycle(x, y, 22, Condition.memoryLabel(ex.id), edit, () -> ex.id = Draft.next(COUNTER_IDS, ex.id));
				}
				case SET_COUNTER -> {
					x = cycle(x, y, 22, Condition.memoryLabel(ex.id), edit, () -> ex.id = Draft.next(COUNTER_IDS, ex.id));
					x = word(x, y, "to");
					x = number(x, y, 40, ex.amount, v -> ex.amount = v, edit);
				}
				case START -> {
					x = cycle(x, y, 26, Condition.memoryLabel(ex.id), edit, () -> ex.id = Draft.next(TIMER_IDS, ex.id));
					x = word(x, y, "for");
					x = decimal(x, y, 40, ex.seconds, v -> ex.seconds = v, edit);
					x = word(x, y, "seconds");
				}
				case STOP -> x = cycle(x, y, 26, Condition.memoryLabel(ex.id), edit, () -> ex.id = Draft.next(TIMER_IDS, ex.id));
				default -> {
					x = text(x, y, 100, ex.id, v -> ex.id = v, edit, false, ex.from);
					x = word(x, y, "from");
					x = cycle(x, y, 40, ex.from.label(), edit, () -> ex.from = Draft.next(List.of(Side.FACES), ex.from));
					x = word(x, y, "to");
					x = cycle(x, y, 40, ex.to.label(), edit, () -> ex.to = Draft.next(List.of(Side.FACES), ex.to));
				}
			}
			int index = m;
			addRenderableWidget(Button.builder(Component.literal("x"), b -> { rule.extras.remove(index); changed(); }).pos(x + 2, y).size(16, 18).build()).active = edit;
			int lx = x0 + 34;
			if (ex.kind == Action.Kind.MOVE && edit) {
				lx = offerButton(lx, y + 20, ex.id, here(false, ex.from), false, moveSuggestion(ex), v -> ex.id = v);
				if (handButton(lx, y + 20, ex.id, false, v -> ex.id = v)) lx += 32;
			}
			extraNowX.add(lx);
			y += ROW;
		}
		Button addExtra = addRenderableWidget(Button.builder(Component.literal("+ Action"), b -> { rule.extras.add(new Draft.Extra()); changed(); })
			.pos(x0, y).size(60, 18).build());
		addExtra.active = edit && rule.extras.size() < Draft.Rule.maxExtras(output);
	}

	private static final List<String> COUNTER_IDS = java.util.stream.IntStream.range(0, Condition.COUNTERS).mapToObj(Condition::counterId).toList();
	private static final List<String> TIMER_IDS = java.util.stream.IntStream.range(0, Condition.TIMERS).mapToObj(Condition::timerId).toList();

	/** A grey word between buttons; returns the x after it. */
	private int word(int x, int y, String text) {
		labels.add(new Label(x + 1, y + 5, text, GRAY));
		return x + font.width(text) + 5;
	}

	private static final List<Action.Kind> OUTPUT_KINDS = List.of(Action.Kind.SET, Action.Kind.PULSE, Action.Kind.TOGGLE, Action.Kind.KEEP);

	/** What the selected BLOCK/ITEMS row could be set to from what the block sees ("use" button), or null. */
	private String suggestion(Draft.Cond c) {
		if (c.side == Side.ANY) return null;
		if (c.kind == Condition.Kind.BLOCK) {
			String seen = snapshot.block(c.side);
			return seen.equals(c.id) ? null : seen;
		}
		return mostIn(c.side, c.id);
	}

	/** The most plentiful item in the container a move takes from, if it isn't already the move's item. */
	private String moveSuggestion(Draft.Extra m) { return mostIn(m.from, m.id); }

	private String mostIn(Side side, String current) {
		var container = snapshot.containers().get(side.ordinal());
		if (container == null || container.isEmpty()) return null;
		String most = container.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).get().getKey();
		return most.equals(current) ? null : most;
	}

	private int cycle(int x, int y, int w, String label, boolean edit, Runnable next) {
		addRenderableWidget(Button.builder(Component.literal(label), b -> { next.run(); changed(); }).pos(x, y).size(w, 18).build()).active = edit;
		return x + w + 2;
	}

	private int number(int x, int y, int w, String value, Consumer<String> set, boolean edit) {
		EditBox box = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal("number")));
		box.setMaxLength(6);
		box.setValue(value);
		box.setResponder(v -> {
			String digits = v.replaceAll("[^0-9]", "");
			if (!digits.equals(v)) { box.setValue(digits); return; }   // numbers only; setValue calls back with the clean text
			set.accept(v);
			if (!v.equals(value)) dirty = true;
		});
		box.setEditable(edit);
		return x + w + 2;
	}

	/** A box for seconds: digits and one decimal point. */
	private int decimal(int x, int y, int w, String value, Consumer<String> set, boolean edit) {
		EditBox box = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal("seconds")));
		box.setMaxLength(7);
		box.setValue(value);
		box.setResponder(v -> {
			String clean = v.replaceAll("[^0-9.]", "");
			int dot = clean.indexOf('.');
			if (dot >= 0) clean = clean.substring(0, dot + 1) + clean.substring(dot + 1).replace(".", "");
			if (!clean.equals(v)) { box.setValue(clean); return; }
			set.accept(v);
			if (!v.equals(value)) dirty = true;
		});
		box.setEditable(edit);
		return x + w + 2;
	}

	/**
	 * A box for a block or item name. {@code side}: where the block or container is that the name is about, for the list
	 * shown while typing ({@link NamePicker}).
	 */
	private int text(int x, int y, int w, String value, Consumer<String> set, boolean edit, boolean block, Side side) {
		EditBox box = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal(block ? "block" : "item")));
		box.setMaxLength(IdPattern.MAX);   // names can have several terms
		box.setValue(value);
		IdCompletion ids = ids(block);
		box.setSuggestion(edit ? ids.suggestion(value) : null);
		box.setHint(Component.literal("stone, *_ore, #logs").withStyle(EditBox.DEFAULT_HINT_STYLE));   // an example, shown while it's empty
		box.addFormatter((shown, from) -> styled(box.getValue(), shown, from, block));
		box.setResponder(v -> {
			set.accept(v.trim().toLowerCase(Locale.ROOT));
			if (!v.equals(value)) dirty = true;
			box.setSuggestion(ids.suggestion(v));   // grey rest of the id; Tab accepts it
		});
		box.setEditable(edit);
		if (edit) idBoxes.put(box, ids);
		nameBoxes.add(new NameBox(box, block, edit, side));
		return x + w + 2;
	}

	/** Colour of a wildcard or tag that stands for something: it means many blocks or items, not one. */
	static final int PATTERN = 0xFFD58CFF;

	/**
	 * A name as it shows while it's edited: each wildcard or tag that stands for something in {@link #PATTERN}, each
	 * "!" term in red, the rest in the usual colour. {@code shown} is the part of {@code text} in view, from {@code from}.
	 */
	private static net.minecraft.util.FormattedCharSequence styled(String text, String shown, int from, boolean block) {
		List<NameText.Term> terms = NameText.terms(text);
		List<net.minecraft.util.FormattedCharSequence> runs = new java.util.ArrayList<>();
		int at = 0;
		while (at < shown.length()) {
			int pos = from + at;
			net.minecraft.network.chat.Style style = net.minecraft.network.chat.Style.EMPTY;
			int end = shown.length();
			for (NameText.Term t : terms) {
				if (pos < t.start()) { end = Math.min(end, t.start() - from); break; }
				if (pos < t.end()) {
					end = Math.min(end, t.end() - from);
					if (t.kind() == NameText.Kind.EXCLUDE) style = style.withColor(NamePanel.RED & 0xFFFFFF);
					else if (t.kind() != NameText.Kind.PLAIN && NamePicker.termOk(t, block, ids(block))) style = style.withColor(PATTERN & 0xFFFFFF);
					break;
				}
			}
			end = Math.max(end, at + 1);
			runs.add(net.minecraft.util.FormattedCharSequence.forward(shown.substring(at, end), style));
			at = end;
		}
		return net.minecraft.util.FormattedCharSequence.composite(runs);
	}

	/** What's really on a side, to check a name against: the container's items with their counts, or the block there (count -1). */
	private java.util.Map<String, Integer> here(boolean block, Side side) {
		if (!block) return side == Side.ANY ? null : snapshot.containers().get(side.ordinal());
		java.util.Map<String, Integer> blocks = new java.util.LinkedHashMap<>();
		for (Side s : side == Side.ANY ? List.of(Side.FACES) : List.of(side)) if (!snapshot.block(s).isEmpty()) blocks.put(snapshot.block(s), -1);
		return blocks;
	}

	private String hereTitle(boolean block, Side side) {
		if (block) return side == Side.ANY ? "Around this block now" : side.label() + " side now";
		return "In the " + snapshot.block(side) + " (" + side.label().toLowerCase(Locale.ROOT) + ")";
	}

	/**
	 * The button under a name: a pattern covering several kinds of what's there ("deepslate_* 3 kinds"), which is how
	 * players meet patterns; else the one thing that's there ("use stone"). Returns the x after it.
	 */
	private int offerButton(int x, int y, String current, java.util.Map<String, Integer> here, boolean block, String single, Consumer<String> set) {
		NamePicker.Offer offer = NamePicker.offer(here, current, block);
		String value = offer != null ? offer.pattern() : single;
		if (value == null) return x;
		Component label = offer != null
			? Component.literal(font.plainSubstrByWidth(offer.pattern(), 90)).withStyle(st -> st.withColor(PATTERN & 0xFFFFFF))
				.append(Component.literal(" " + offer.kinds() + " kinds").withStyle(ChatFormatting.WHITE))
			: Component.literal(font.plainSubstrByWidth("use " + single, 96));
		int w = font.width(label) + 8;
		addRenderableWidget(Button.builder(label, b -> { set.accept(value); changed(); }).pos(x, y).size(w, 13).build());
		return x + w + 2;
	}

	/** Every block or item id the game knows, for completion. */
	private static IdCompletion ids(boolean block) {
		if (block) {
			if (blockIds == null) blockIds = new IdCompletion(BuiltInRegistries.BLOCK.keySet().stream().map(Identifier::toString).toList(),
				BuiltInRegistries.BLOCK.getTags().map(t -> t.key().location().toString()).toList());
			return blockIds;
		}
		if (itemIds == null) itemIds = new IdCompletion(BuiltInRegistries.ITEM.keySet().stream().map(Identifier::toString).toList(),
			BuiltInRegistries.ITEM.getTags().map(t -> t.key().location().toString()).toList());
		return itemIds;
	}

	/**
	 * With the list open under a name: the arrow keys choose a row, Enter or Tab takes it (Tab also takes the completion
	 * when no row was chosen), Esc puts the list away. Otherwise Tab in a name box accepts its grey suggestion, or moves
	 * between boxes as usual.
	 */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
		int k = key.key();
		if (k == com.mojang.blaze3d.platform.InputConstants.KEY_DELETE && matchesShown != null && matchesShown.count() > 0 && matchesBox != null && matchesCanLeaveOut) {
			leaveOutChosen();
			return true;
		}
		if (pickerLayout != null && pickerView != null && getFocused() == pickerBox) {
			List<NamePicker.Row> rows = pickerView.rows();
			if (k == com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE) { pickerClosed = true; pickerLayout = null; return true; }
			if (k == com.mojang.blaze3d.platform.InputConstants.KEY_DOWN || k == com.mojang.blaze3d.platform.InputConstants.KEY_UP) {
				int step = k == com.mojang.blaze3d.platform.InputConstants.KEY_DOWN ? 1 : -1;
				int at = pickIndex >= 0 ? pickIndex : pickerView.tabRow() >= 0 ? pickerView.tabRow() : step > 0 ? -1 : rows.size();
				for (int n = 0; n < rows.size(); n++) {
					at = Math.floorMod(at + step, rows.size());
					if (rows.get(at).value() != null) { pickIndex = at; break; }
				}
				return true;
			}
			boolean enter = k == com.mojang.blaze3d.platform.InputConstants.KEY_RETURN || k == com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER;
			int row = pickIndex >= 0 ? pickIndex : enter ? -1 : pickerView.tabRow();
			if ((enter || k == com.mojang.blaze3d.platform.InputConstants.KEY_TAB) && row >= 0 && row < rows.size() && rows.get(row).value() != null) {
				pick(rows.get(row));
				return true;
			}
		}
		if (k == com.mojang.blaze3d.platform.InputConstants.KEY_TAB && getFocused() instanceof EditBox box && idBoxes.containsKey(box)) {
			String full = idBoxes.get(box).complete(box.getValue());
			if (full != null) {
				box.setValue(full);
				box.moveCursorToEnd(false);
				return true;
			}
		}
		return super.keyPressed(key);
	}

	/** A click on a row of the list takes it; a click elsewhere on the list does nothing (it doesn't reach what's under it). */
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (pickerLayout != null && pickerView != null && pickerLayout.contains(event.x(), event.y())) {
			int row = pickerLayout.rowAt(event.x(), event.y());
			if (row >= 0) pick(pickerView.rows().get(row));
			return true;
		}
		boolean handled = super.mouseClicked(event, doubleClick);
		// A click on nothing leaves the box being typed in (the game would keep the cursor there): its name becomes
		// chips and its list goes. A click in the box brings the list back after Esc.
		if (getFocused() instanceof EditBox box) {
			if (!box.isMouseOver(event.x(), event.y())) setFocused(null);
			else if (box == pickerBox) pickerClosed = false;
		}
		return handled;
	}

	/** Puts a row's name in place of the term the cursor is in. */
	private void pick(NamePicker.Row row) {
		if (pickerBox == null || row.value() == null) return;
		NameText.Typing typing = NameText.typing(pickerBox.getValue(), pickerBox.getCursorPosition());
		pickerBox.setValue(typing.replace(row.value()));
		pickerBox.moveCursorTo(typing.caret(row.value()), false);
		pickIndex = -1;
	}

	/**
	 * Delete while the matches are showing: leaves the chosen one out of that name ("!oak_log"). From a tag or pattern
	 * row of the list, the row is taken first, so it's "that tag, without this one".
	 */
	private void leaveOutChosen() {
		EditBox box = matchesBox.box();
		String id = matchesShown.all().get(Math.max(0, Math.min(matchesChosen, matchesShown.count() - 1)));
		String text = box.getValue();
		if (matchesRow != null) text = NameText.typing(text, box.getCursorPosition()).replace(matchesRow);
		String next = NameText.leaveOut(text, id);
		if (next.length() > IdPattern.MAX) return;
		box.setValue(next);
		box.moveCursorToEnd(false);
	}

	/** Works out the list for the name box that has the keyboard, if any; again only when its text or surroundings change. */
	private void updatePicker(Painter p) {
		NameBox name = null;
		if (getFocused() instanceof EditBox focused) for (NameBox n : nameBoxes) if (n.box() == focused && n.edit()) name = n;
		pickerName = name;
		if (name == null) { pickerBox = null; pickerView = null; pickerLayout = null; return; }
		EditBox box = name.box();
		if (box != pickerBox) { pickerBox = box; pickerText = null; pickerCursor = -1; pickerClosed = false; pickIndex = -1; }
		String text = box.getValue();
		int cursor = box.getCursorPosition();
		java.util.Map<String, Integer> here = here(name.block(), name.side());
		if (pickerView == null || !text.equals(pickerText) || cursor != pickerCursor || !java.util.Objects.equals(here, pickerHere)) {
			if (pickerText != null && !text.equals(pickerText)) pickerClosed = false;   // typing brings it back
			if (!text.equals(pickerText) || cursor != pickerCursor) pickIndex = -1;
			pickerText = text;
			pickerCursor = cursor;
			pickerHere = here == null ? null : new java.util.HashMap<>(here);
			pickerView = NamePicker.view(text, cursor, name.block(), ids(name.block()), here, hereTitle(name.block(), name.side()));
			if (pickIndex >= pickerView.rows().size()) pickIndex = -1;
		}
		pickerLayout = pickerClosed ? null : NamePanel.layout(p, pickerView, box.getX(), box.getY(), box.getHeight(), width, height);
	}

	/**
	 * What's drawn over the widgets: names as chips in the boxes not being typed in, the list under the one that is, and
	 * what a name matches when the pointer is on it.
	 */
	private void drawNames(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Painter p = new GuiPainter(g, font);
		updatePicker(p);
		g.nextStratum();
		for (NameBox n : nameBoxes) {
			if (n.box() == getFocused()) continue;
			String text = n.box().getValue();
			List<NameText.Term> terms = NameText.terms(text);
			if (terms.isEmpty() || !IdPattern.isPattern(text)) continue;   // a plain name stays plain text
			List<Boolean> ok = terms.stream().map(t -> NamePicker.termOk(t, n.block(), ids(n.block()))).toList();
			NamePanel.chips(p, terms, ok, n.box().getX(), n.box().getY(), n.box().getWidth(), n.box().getHeight(), !n.edit());
		}
		matchesShown = null;
		if (pickerLayout != null) {
			g.nextStratum();
			int hovered = pickerLayout.rowAt(mouseX, mouseY);
			NamePanel.draw(p, pickerView, pickerLayout, pickerName.block(), hovered >= 0 ? hovered : pickIndex >= 0 ? pickIndex : pickerView.tabRow());
			// A tag or pattern in the list, under the pointer or chosen with the arrow keys: what it holds, beside the list.
			int row = hovered >= 0 ? hovered : pickIndex;
			List<NamePicker.Row> rows = pickerView.rows();
			if (row >= 0 && row < rows.size() && rows.get(row).special() && rows.get(row).value() != null) {
				NamePicker.Summary summary = NamePicker.summary(rows.get(row).value(), pickerName.block(), ids(pickerName.block()));
				if (summary != null) {
					int rowY = pickerLayout.y();
					for (NamePanel.Hit hit : pickerLayout.hits()) if (hit.row() == row) rowY = hit.y();
					int[] size = NamePanel.hoverSize(p, summary, pickerName.block(), true);
					matches(g, p, summary, pickerName, rows.get(row).value(), true, NamePanel.beside(pickerLayout, rowY, size[0], size[1], width, height));
					return;
				}
			}
			if (pickerLayout.contains(mouseX, mouseY)) return;
		}
		for (NameBox n : nameBoxes) {
			if (!n.box().isMouseOver(mouseX, mouseY) || (pickerLayout != null && n.box() == pickerBox)) continue;
			NamePicker.Summary summary = NamePicker.summary(n.box().getValue(), n.block(), ids(n.block()));
			if (summary == null) continue;
			// Not while a box is being typed in: there Delete deletes a letter.
			boolean canLeaveOut = n.edit() && !(getFocused() instanceof EditBox);
			int[] size = NamePanel.hoverSize(p, summary, n.block(), canLeaveOut);
			matches(g, p, summary, n, null, canLeaveOut, NamePanel.byPointer(mouseX, mouseY, size[0], size[1], width, height));
			return;
		}
		matchesKey = null;
		matchesBox = null;
	}

	/** {@code row}: the tag or pattern of the list's row these are the matches of, or null for the box's whole name. */
	private void matches(GuiGraphicsExtractor g, Painter p, NamePicker.Summary summary, NameBox box, String row, boolean canLeaveOut, int[] at) {
		// The same box keeps its place in the grid while its name changes (Delete leaves one out, the next is chosen).
		String key = nameBoxes.indexOf(box) + "|" + (row == null ? "" : row);
		if (!key.equals(matchesKey)) { matchesKey = key; matchesChosen = 0; }
		matchesChosen = Math.max(0, Math.min(matchesChosen, summary.count() - 1));
		matchesShown = summary;
		matchesBox = box;
		matchesRow = row;
		matchesCanLeaveOut = canLeaveOut;
		g.nextStratum();
		NamePanel.hover(p, summary, box.block(), at[0], at[1], matchesChosen, canLeaveOut);
	}

	/** The scroll wheel goes through what a pattern or tag matches while that panel is showing, naming each. */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (matchesShown != null && matchesShown.count() > 1 && scrollY != 0) {
			matchesChosen = Math.floorMod(matchesChosen + (scrollY < 0 ? 1 : -1), matchesShown.count());
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	/** The id of what the player holds (main hand, else off hand): a block's id for block checks. Null if nothing fits. */
	private String held(boolean block) {
		if (minecraft == null || minecraft.player == null) return null;
		for (var stack : List.of(minecraft.player.getMainHandItem(), minecraft.player.getOffhandItem())) {
			if (stack.isEmpty()) continue;
			if (!block) return IdCompletion.shortId(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
			if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) return IdCompletion.shortId(BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString());
		}
		return null;
	}

	/** "hand" fills the box with what you hold; shown when that's different from the box. */
	/** Returns whether it added the button. */
	private boolean handButton(int x, int y, String current, boolean block, Consumer<String> set) {
		String h = held(block);
		if (h == null || h.equals(current)) return false;
		addRenderableWidget(Button.builder(Component.literal("hand"), b -> { set.accept(h); changed(); }).pos(x, y).size(30, 13).build());
		return true;
	}

	private void changed() { dirty = true; rebuildWidgets(); }

	private void moveRule(int d) {
		List<Draft.Rule> list = list();
		int to = sel() + d;
		list.add(to, list.remove(sel()));
		select(to);
		changed();
	}

	// ---- evaluation against the live snapshot ----

	private record Check(boolean ok, String reading) {}

	private Check check(Draft.Cond c) {
		Condition cond = c.toCondition();
		Program one = new Program(List.of(new Program.Rule(List.of(cond), Action.set(0))), Action.set(0));
		int[] before = null;
		if (cond.kind() == Condition.Kind.CHANGE && previous != null) {
			before = Engine.unknownSignals();
			before[cond.side().ordinal()] = previous.signal(cond.side());
		}
		Engine.Evaluation e = Engine.evaluate(one, snapshot, before);
		return new Check(e.truth()[0][0] && cond.problem() == null, e.readings()[0][0]);
	}

	private boolean allTrue(Draft.Rule rule) {
		for (Draft.Cond c : rule.conditions) if (!check(c).ok()) return false;
		return true;
	}

	/** First draft output rule whose conditions all hold right now, or -1. */
	private int draftMatched() {
		for (int r = 0; r < draft.rules.size(); r++) if (allTrue(draft.rules.get(r))) return r;
		return -1;
	}

	/** What an extra action works on, right now: a move's containers, a counter's value, a timer's state. */
	private String extraReading(Draft.Extra ex) {
		if (ex.kind.counter()) {
			int i = Condition.counterIndex(ex.id);
			return i < 0 ? "" : Condition.memoryLabel(ex.id) + " = " + snapshot.counter(i);
		}
		if (ex.kind == Action.Kind.START || ex.kind == Action.Kind.STOP) {
			int i = Condition.timerIndex(ex.id);
			if (i < 0) return "";
			int left = snapshot.timerLeft(i);
			return Condition.memoryLabel(ex.id) + (left > 0 ? " running, " + Action.seconds(left) + " left" : " not running");
		}
		return moveReading(ex);
	}

	/** What a move would see now: "up: hopper, 3 diamond -> down: chest". */
	private String moveReading(Draft.Extra m) {
		var from = snapshot.containers().get(m.from.ordinal());
		var to = snapshot.containers().get(m.to.ordinal());
		String fromText = m.from.label().toLowerCase(Locale.ROOT) + ": " + snapshot.block(m.from)
			+ (from == null ? " (no container)" : ", " + snapshot.items(m.from, m.id) + " " + (m.id.equals("*") ? "items" : m.id));
		String toText = m.to.label().toLowerCase(Locale.ROOT) + ": " + snapshot.block(m.to) + (to == null ? " (no container)" : "");
		return fromText + " -> " + toText;
	}

	private boolean moveReady(Draft.Extra m) {
		return snapshot.containers().get(m.from.ordinal()) != null && snapshot.containers().get(m.to.ordinal()) != null && m.from != m.to;
	}

	// ---- copy, paste, presets ----

	/** Puts the rules being edited on the clipboard as one line of text ("logicblocks:..."), to paste into another block or share. */
	private void copy() {
		Draft.Built built = build();
		if (built.problem() != null) { status = "Fix this before copying: " + built.problem(); statusColor = RED; return; }
		minecraft.keyboardHandler.setClipboard(built.program().toText());
		status = "Copied. Paste into another Logic Block's editor, or share the text.";
		statusColor = GREEN;
	}

	private void paste() {
		Program p;
		try { p = Program.fromText(minecraft.keyboardHandler.getClipboard()); }
		catch (IllegalArgumentException e) { status = e.getMessage() + ". Copy rules with the Copy button first."; statusColor = RED; return; }
		replace(p, "the copied rules");
	}

	/** Replaces the rules being edited with {@code p}, after asking if there's anything to lose. Nothing is saved until Save. */
	void replace(Program p, String what) {
		if (draft.rules.isEmpty() && draft.also.isEmpty()) { use(p, what); return; }
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) use(p, what);
			minecraft.gui.setScreen(this);
		}, Component.literal("Replace these rules with " + what + "?"), Component.literal("Nothing changes on the block until you Save."),
			Component.literal("Replace"), Component.literal("Cancel")));
	}

	private void use(Program p, String what) {
		draft = new Draft(p);
		selected = 0; selectedAlso = 0;
		alsoTab = draft.rules.isEmpty() && !draft.also.isEmpty();
		dirty = true;
		status = "Loaded " + what + ". Check the sides and items, then Save.";
		statusColor = GOLD;
		rebuildWidgets();
	}

	// ---- saving and closing ----

	private Draft.Built build() {
		return draft.build(id -> known(id, true), id -> known(id, false));
	}

	/**
	 * Whether the game has a block or item by this id, or for a pattern ("deepslate_*", "#slabs, !*oak*") whether every
	 * tag it names exists and it fits at least one.
	 */
	private static boolean known(String id, boolean block) {
		if (dev.whizzle.logic.model.IdPattern.isPattern(id)) {
			for (String tag : dev.whizzle.logic.model.IdPattern.tagsIn(id)) if (!ids(block).knownTag(tag)) return false;
			for (Identifier k : block ? BuiltInRegistries.BLOCK.keySet() : BuiltInRegistries.ITEM.keySet())
				if (dev.whizzle.logic.model.IdPattern.matches(id, IdCompletion.shortId(k.toString()), block)) return true;
			return false;
		}
		Identifier key = Identifier.tryParse(id);
		if (key == null) return false;
		return block ? BuiltInRegistries.BLOCK.containsKey(key) : BuiltInRegistries.ITEM.containsKey(key);
	}

	private void save() {
		Draft.Built built = build();
		if (built.problem() != null) { status = built.problem(); statusColor = RED; return; }
		Net.send(Messages.SAVE, Messages.save(open.x(), open.y(), open.z(), built.program()));
		status = "Saving...";
		statusColor = GRAY;
	}

	@Override
	public void onClose() {
		if (dirty && open.editable()) {
			minecraft.gui.setScreen(new ConfirmScreen(discard -> {
				if (discard) { dirty = false; closeForGood(); }
				else minecraft.gui.setScreen(this);
			}, Component.literal("Discard your changes?"), Component.literal("These rules haven't been saved to the Logic Block."),
				Component.literal("Discard"), Component.literal("Keep editing")));
			return;
		}
		closeForGood();
	}

	private void closeForGood() {
		if (!closing) { closing = true; Net.send(Messages.CLOSE, Messages.close()); }
		minecraft.gui.setScreen(null);
	}

	@Override
	public boolean isPauseScreen() { return false; }

	// ---- drawing ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		g.pose().pushMatrix();
		g.pose().translate(left, 0);
		drawContent(g);
		g.pose().popMatrix();
		drawNames(g, mouseX, mouseY);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		if (left > 0) g.fill(left - 6, 0, left + cw + 6, height, 0x50000000);   // the column, on wide screens
	}

	private void drawContent(GuiGraphicsExtractor g) {
		int x0 = rightX();
		g.text(font, "Logic Block", 6, 9, AQUA);
		String out = "Output " + snapshot.output() + "   by " + open.owner() + "   " + open.x() + " " + open.y() + " " + open.z();
		g.text(font, font.plainSubstrByWidth(out, cw - 374), 76, 9, GRAY);
		if (!alsoTab) g.text(font, "Otherwise", 6, height - 19, WHITE);
		List<Draft.Rule> list = list();
		if (list.isEmpty()) {
			String hint = alsoTab
				? "When rules: every true one runs, alongside If/Else. For moving items, counters and timers. \"+ When\" adds one."
				: "If/Else rules: the first true one sets the output, \"Otherwise\" covers the rest. \"+ Rule\" adds one.";
			drawWrapped(g, hint, x0, 30, cw - x0 - 8, GRAY);
		} else {
			Draft.Rule rule = list.get(sel());
			boolean now = alsoTab ? allTrue(rule) : draftMatched() == sel();
			String title = (alsoTab ? "When rule " : "Rule ") + (sel() + 1) + (now ? (alsoTab ? "  (true: runs now)" : "  (true right now)") : "")
				+ (alsoTab && rule.conditions.isEmpty() ? "  (no conditions: always)" : "");
			g.text(font, title, x0, 30, now ? GREEN : WHITE);
			int y = 42;
			for (Draft.Cond c : rule.conditions) {
				if (c.kind == Condition.Kind.PLAYERS) g.text(font, "within", x0 + 50, y + 5, GRAY);
				Check k = check(c);
				String line = "now: " + k.reading() + (k.ok() ? "  (true)" : "  (false)");
				// The buttons under the row were placed by init: start the line after them (computing it again here could
				// disagree with what init placed, and the text then ran over the "hand" button).
				int ci = rule.conditions.indexOf(c);
				int lx = ci >= 0 && ci < condNowX.size() ? condNowX.get(ci) : x0 + 50;
				g.text(font, font.plainSubstrByWidth(line, cw - lx - 6), lx, y + 23, k.ok() ? GREEN : RED);
				y += ROW;
			}
			y += 24;
			if (!alsoTab) {
				g.text(font, "Then", x0, y + 5, WHITE);
				int px = x0 + 34 + 54 + 24 + 2;
				if (rule.action == Action.Kind.PULSE) g.text(font, "for", px, y + 5, GRAY);
				if (rule.action == Action.Kind.PULSE) g.text(font, "ticks (20 = 1 s)", px + 24 + 36, y + 5, GRAY);
				y += 24;
			}
			for (Label l : labels) g.text(font, l.text(), l.x(), l.y(), l.color());
			for (int m = 0; m < rule.extras.size() && m < extraNowX.size(); m++) {
				Draft.Extra ex = rule.extras.get(m);
				int lx = extraNowX.get(m);
				boolean move = ex.kind == Action.Kind.MOVE;
				g.text(font, font.plainSubstrByWidth("now: " + extraReading(ex), cw - lx - 6), lx, y + 23, move ? (moveReady(ex) ? GREEN : RED) : GRAY);
				y += ROW;
			}
			y += 22;
			if (rule.extras.stream().anyMatch(ex -> ex.kind == Action.Kind.MOVE))
				y = drawWrapped(g, "Moves one item every 8 ticks (hopper speed), between containers touching this block, every check while the rule is true.", x0, y, cw - x0 - 6, GRAY);
			if (rule.extras.stream().anyMatch(ex -> ex.kind != Action.Kind.MOVE))
				y = drawWrapped(g, "Counter and timer actions run once each time the rule becomes true. Counters and timers belong to this block; saving starts them over.", x0, y, cw - x0 - 6, GRAY);
			String warn = dustWarning(rule);
			if (warn != null) { g.text(font, font.plainSubstrByWidth(warn, cw - x0 - 6), x0, y, GOLD); y += 12; }
		}
		int sx = Math.max(x0, 140);
		if (!status.isEmpty()) g.text(font, font.plainSubstrByWidth(status, cw - sx - 6), sx, height - 19, statusColor);
		else if (dirty) g.text(font, "Not saved yet", sx, height - 19, GOLD);
	}

	/** Draws text wrapped to {@code w}; returns the y below it. */
	private int drawWrapped(GuiGraphicsExtractor g, String text, int x, int y, int w, int color) {
		for (var line : font.split(Component.literal(text), Math.max(40, w))) { g.text(font, line, x, y, color); y += 11; }
		return y;
	}

	/** Dust touching the block carries its own output back in; suggest a repeater when a signal rule reads such a side. */
	private String dustWarning(Draft.Rule rule) {
		for (Draft.Cond c : rule.conditions) {
			if (c.kind != Condition.Kind.SIGNAL && c.kind != Condition.Kind.CHANGE) continue;
			for (Side s : c.side == Side.ANY ? List.of(Side.FACES) : List.of(c.side))
				if (snapshot.block(s).equals("redstone_wire"))
					return "! Dust on the " + s.label().toLowerCase(Locale.ROOT) + " side also carries this block's own output back in. Feed inputs through a repeater.";
		}
		return null;
	}

	static String sideLabel(Side s) { return s == Side.ANY ? "Any side" : s.label(); }

	static String kindLabel(Condition.Kind k) {
		return switch (k) { case BLOCK -> "Block"; case SIGNAL -> "Signal"; case ITEMS -> "Items"; case PLAYERS -> "Players"; case CHANGE -> "Change";
			case COUNTER -> "Counter"; case TIMER -> "Timer"; case FILL -> "Fill"; };
	}

	static String actionLabel(Action.Kind k) {
		return switch (k) { case SET -> "Output"; case PULSE -> "Pulse"; case TOGGLE -> "Toggle"; case KEEP -> "Keep"; default -> extraLabel(k); };
	}

	static String extraLabel(Action.Kind k) {
		return switch (k) { case MOVE -> "Move"; case ADD -> "Add"; case SUBTRACT -> "Subtract"; case SET_COUNTER -> "Set"; case START -> "Start";
			case STOP -> "Stop"; default -> actionLabel(k); };
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
	static String summary(Draft.Rule r, boolean output) {
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < r.conditions.size(); i++) {
			Draft.Cond c = r.conditions.get(i);
			if (i > 0) sb.append(" & ");
			sb.append(switch (c.kind) {
				case BLOCK -> c.side.label().toLowerCase(Locale.ROOT) + " " + c.op.symbol + " " + c.id;
				case SIGNAL -> "sig " + (c.side == Side.ANY ? "any" : c.side.label().toLowerCase(Locale.ROOT)) + " " + c.op.symbol + c.number;
				case ITEMS -> c.id + " " + c.op.symbol + c.number;
				case PLAYERS -> "players " + c.op.symbol + c.number;
					case CHANGE -> (c.side == Side.ANY ? "any" : c.side.label().toLowerCase(Locale.ROOT)) + (c.turnsOn() ? " on" : " off");
					case COUNTER -> Condition.memoryLabel(c.id) + c.op.symbol + c.number;
					case TIMER -> Condition.memoryLabel(c.id) + (c.op == Condition.Op.IS ? " " : " not ") + (c.running() ? "running" : "done");
					case FILL -> "fill " + c.side.label().toLowerCase(Locale.ROOT) + c.op.symbol + c.number;
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
}
