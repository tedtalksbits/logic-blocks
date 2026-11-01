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
 * with what the block sees right now and true/false, then what it does: an output, and moves. Edits are checked live
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
	private static IdCompletion blockIds, itemIds;

	private final Messages.Open open;
	private final Draft draft;
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
		listW = Math.clamp(cw / 4, 112, 180);
		selected = Math.min(selected, Math.max(0, draft.rules.size() - 1));
		selectedAlso = Math.min(selectedAlso, Math.max(0, draft.also.size() - 1));

		// Top bar
		Button save = addRenderableWidget(Button.builder(Component.literal("Save"), b -> save()).pos(cw - 110, 4).size(50, 18).build());
		save.active = edit;
		addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose()).pos(cw - 56, 4).size(50, 18).build());
		addRenderableWidget(Button.builder(Component.literal("Labels"), b -> dev.whizzle.logic.client.LogicClient.openSettings(this))
			.pos(cw - 164, 4).size(50, 18).build());

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
					x = text(x, y, 86, cond.id, v -> cond.id = v, edit, true);
				}
				case SIGNAL -> {
					x = cycle(x, y, 44, sideLabel(cond.side), edit, () -> cond.side = Draft.next(cond.sides(), cond.side));
					x = cycle(x, y, 22, cond.op.symbol, edit, () -> cond.op = Draft.next(cond.ops(), cond.op));
					x = number(x, y, 24, cond.number, v -> cond.number = v, edit);
				}
				case ITEMS -> {
					x = cycle(x, y, 44, cond.side.label(), edit, () -> cond.side = Draft.next(cond.sides(), cond.side));
					x = text(x, y, 70, cond.id, v -> cond.id = v, edit, false);
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
			}
			int index = c;
			addRenderableWidget(Button.builder(Component.literal("x"), b -> { rule.conditions.remove(index); changed(); }).pos(x + 2, y).size(16, 18).build()).active = edit;
			if (edit && (cond.kind == Condition.Kind.BLOCK || cond.kind == Condition.Kind.ITEMS)) {
				int bx = x0 + 48;
				if (suggestion(cond) != null) { addRenderableWidget(Button.builder(Component.literal("use"), b -> { cond.id = suggestion(cond); changed(); }).pos(bx, y + 20).size(28, 13).build()); bx += 30; }
				handButton(bx, y + 20, cond.id, cond.kind == Condition.Kind.BLOCK, v -> cond.id = v);
			}
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
		for (int m = 0; m < rule.moves.size(); m++) {
			Draft.Move move = rule.moves.get(m);
			int x = x0 + 34;   // "and" / "Do" label
			x += font.width("Move") + 6;
			x = text(x, y, 70, move.id, v -> move.id = v, edit, false);
			x += font.width("from") + 4;
			x = cycle(x, y, 40, move.from.label(), edit, () -> move.from = Draft.next(List.of(Side.FACES), move.from));
			x += font.width("to") + 4;
			x = cycle(x, y, 40, move.to.label(), edit, () -> move.to = Draft.next(List.of(Side.FACES), move.to));
			int index = m;
			addRenderableWidget(Button.builder(Component.literal("x"), b -> { rule.moves.remove(index); changed(); }).pos(x + 2, y).size(16, 18).build()).active = edit;
			String use = moveSuggestion(move);
			if (edit) {
				int bx = x0 + 34;
				if (use != null) { addRenderableWidget(Button.builder(Component.literal("use"), b -> { move.id = use; changed(); }).pos(bx, y + 20).size(28, 13).build()); bx += 30; }
				handButton(bx, y + 20, move.id, false, v -> move.id = v);
			}
			y += ROW;
		}
		Button addMove = addRenderableWidget(Button.builder(Component.literal("+ Move items"), b -> { rule.moves.add(new Draft.Move()); changed(); })
			.pos(x0, y).size(76, 18).build());
		addMove.active = edit && rule.moves.size() < Draft.Rule.maxMoves(output);
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
	private String moveSuggestion(Draft.Move m) { return mostIn(m.from, m.id); }

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

	private int text(int x, int y, int w, String value, Consumer<String> set, boolean edit, boolean block) {
		EditBox box = addRenderableWidget(new EditBox(font, x, y, w, 18, Component.literal(block ? "block" : "item")));
		box.setMaxLength(Condition.MAX_ID);
		box.setValue(value);
		IdCompletion ids = ids(block);
		box.setSuggestion(edit ? ids.suggestion(value) : null);
		box.setResponder(v -> {
			set.accept(v.trim().toLowerCase(Locale.ROOT));
			if (!v.equals(value)) dirty = true;
			box.setSuggestion(ids.suggestion(v));   // grey rest of the id; Tab accepts it
		});
		box.setEditable(edit);
		if (edit) idBoxes.put(box, ids);
		return x + w + 2;
	}

	/** Every block or item id the game knows, for completion. */
	private static IdCompletion ids(boolean block) {
		if (block) {
			if (blockIds == null) blockIds = new IdCompletion(BuiltInRegistries.BLOCK.keySet().stream().map(Identifier::toString).toList());
			return blockIds;
		}
		if (itemIds == null) itemIds = new IdCompletion(BuiltInRegistries.ITEM.keySet().stream().map(Identifier::toString).toList());
		return itemIds;
	}

	/** Tab in a block or item box accepts its suggestion (otherwise Tab moves between boxes as usual). */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
		if (key.key() == com.mojang.blaze3d.platform.InputConstants.KEY_TAB && getFocused() instanceof EditBox box && idBoxes.containsKey(box)) {
			String full = idBoxes.get(box).complete(box.getValue());
			if (full != null) {
				box.setValue(full);
				box.moveCursorToEnd(false);
				return true;
			}
		}
		return super.keyPressed(key);
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
	private void handButton(int x, int y, String current, boolean block, Consumer<String> set) {
		String h = held(block);
		if (h == null || h.equals(current)) return;
		addRenderableWidget(Button.builder(Component.literal("hand"), b -> { set.accept(h); changed(); }).pos(x, y).size(30, 13).build());
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

	/** What a move would see now: "up: hopper, 3 diamond -> down: chest". */
	private String moveReading(Draft.Move m) {
		var from = snapshot.containers().get(m.from.ordinal());
		var to = snapshot.containers().get(m.to.ordinal());
		String fromText = m.from.label().toLowerCase(Locale.ROOT) + ": " + snapshot.block(m.from)
			+ (from == null ? " (no container)" : ", " + snapshot.items(m.from, m.id) + " " + (m.id.equals("*") ? "items" : m.id));
		String toText = m.to.label().toLowerCase(Locale.ROOT) + ": " + snapshot.block(m.to) + (to == null ? " (no container)" : "");
		return fromText + " -> " + toText;
	}

	private boolean moveReady(Draft.Move m) {
		return snapshot.containers().get(m.from.ordinal()) != null && snapshot.containers().get(m.to.ordinal()) != null && m.from != m.to;
	}

	// ---- saving and closing ----

	private Draft.Built build() {
		return draft.build(id -> known(id, true), id -> known(id, false));
	}

	private static boolean known(String id, boolean block) {
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
		g.text(font, font.plainSubstrByWidth(out, cw - 244), 76, 9, GRAY);
		if (!alsoTab) g.text(font, "Otherwise", 6, height - 19, WHITE);
		List<Draft.Rule> list = list();
		if (list.isEmpty()) {
			String hint = alsoTab
				? "When rules: every true one runs, every check, alongside If/Else. For moving items. \"+ When\" adds one."
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
				int lx = x0 + 50;
				if ((c.kind == Condition.Kind.BLOCK || c.kind == Condition.Kind.ITEMS) && open.editable()) {
					int bx = x0 + 48;
					if (suggestion(c) != null) bx += 30;
					String h = held(c.kind == Condition.Kind.BLOCK);
					if (h != null && !h.equals(c.id)) bx += 32;
					lx = Math.max(lx, bx + 2);
				}
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
			for (int m = 0; m < rule.moves.size(); m++) {
				Draft.Move move = rule.moves.get(m);
				g.text(font, alsoTab && m == 0 ? "Do" : "and", x0, y + 5, WHITE);
				int x = x0 + 34;
				g.text(font, "Move", x, y + 5, WHITE);
				x += font.width("Move") + 6 + 72;
				g.text(font, "from", x, y + 5, GRAY);
				x += font.width("from") + 4 + 42;
				g.text(font, "to", x, y + 5, GRAY);
				int lx = x0 + 34;
				if (open.editable()) {
					if (moveSuggestion(move) != null) lx += 30;
					String h = held(false);
					if (h != null && !h.equals(move.id)) lx += 32;
				}
				g.text(font, font.plainSubstrByWidth("now: " + moveReading(move), cw - lx - 6), lx, y + 23, moveReady(move) ? GREEN : RED);
				y += ROW;
			}
			y += 22;
			if (!rule.moves.isEmpty()) {
				y = drawWrapped(g, "Moves one item every 8 ticks (hopper speed), between containers touching this block.", x0, y, cw - x0 - 6, GRAY);
			}
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
		return switch (k) { case BLOCK -> "Block"; case SIGNAL -> "Signal"; case ITEMS -> "Items"; case PLAYERS -> "Players"; case CHANGE -> "Change"; };
	}

	static String actionLabel(Action.Kind k) {
		return switch (k) { case SET -> "Output"; case PULSE -> "Pulse"; case TOGGLE -> "Toggle"; case KEEP -> "Keep"; case MOVE -> "Move"; };
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
			});
		}
		if (r.conditions.isEmpty()) sb.append("always");
		if (output) {
			sb.append(" -> ").append(switch (r.action) {
				case SET -> r.strength; case PULSE -> "pulse " + r.strength; case TOGGLE -> "toggle " + r.strength; case KEEP -> "keep"; case MOVE -> "?";
			});
			if (!r.moves.isEmpty()) sb.append(" + move");
		} else {
			sb.append(": move ");
			for (int i = 0; i < r.moves.size(); i++) sb.append(i == 0 ? "" : ", ").append(r.moves.get(i).id);
		}
		return sb.toString();
	}
}
