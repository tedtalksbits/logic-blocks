package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.client.net.Messages;
import dev.whizzle.logic.client.net.Net;
import dev.whizzle.logic.client.ui.EditorLayout.El;
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

/**
 * The rule editor. Where everything goes, and what each button does to the rules, is worked out by
 * {@link EditorLayout} (pure Java, tested without the game); this class turns that into the game's buttons and boxes,
 * draws the editor's own parts over them ({@link EditorPaint}), and runs what pops up: the list under a name being
 * typed ({@link NamePicker}), the list of options a button opens ({@link OptionPanel}), the map of sides
 * ({@link SideMap}), what a pattern matches, and what a lamp sees.
 */
public final class LogicScreen extends Screen {
	static final int WHITE = 0xFFFFFFFF, GRAY = 0xFFB8C0CC, GREEN = 0xFF6EE07A, RED = 0xFFFF6B6B, GOLD = 0xFFFFC857, AQUA = 0xFF6FD3FF;
	/** The editor is laid out in a centred column at most this wide (GUI pixels), like a page with a max width. */
	static final int MAX_WIDTH = 1080;
	/** Content width and left edge of the centred column. */
	private int cw, left;
	private final EditorLayout.State state = new EditorLayout.State();
	private EditorLayout.Result layout;
	/** The game's button for each element that opens a list or is a card (the editor draws their labels itself). */
	private final java.util.Map<El, Button> buttons = new java.util.HashMap<>();
	private final java.util.Map<EditBox, IdCompletion> idBoxes = new java.util.HashMap<>();
	/** A block or item name box, the side whose block or container its name is checked against, and its element. */
	private record NameBox(EditBox box, boolean block, boolean edit, Side side, El el) {}
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
	private int pickerCursor = -1;
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
	/**
	 * The list of options (or map of sides) a button has opened: its element, the option chosen with the arrow keys
	 * (-1: none), and where it was last drawn, for clicks.
	 */
	private El popup;
	private int popupKey = -1;
	/** Counts the frames drawn (see {@link EditorLayout.Inputs#frame}). */
	private long frame;
	private List<EditorLayout.Option> popupOptions;
	private OptionPanel.Layout popupList;
	private SideMap.Layout popupMap;
	private static IdCompletion blockIds, itemIds;

	private final Messages.Open open;
	private Draft draft;
	private Snapshot snapshot, previous;
	private boolean dirty, closing;
	private String status = "";
	private int statusColor = GRAY;

	public LogicScreen(Messages.Open open) {
		super(Component.literal("Logic Block"));
		this.open = open;
		this.draft = new Draft(open.program());
		this.snapshot = open.snapshot();
		NamePicker.clearCache();   // tags may have changed since the editor was last open
		// A block with only "when" rules (a sorter station, say) opens on them.
		state.alsoTab = draft.rules.isEmpty() && !draft.also.isEmpty();
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

	@Override
	protected void init() {
		cw = Math.min(width, MAX_WIDTH);
		left = (width - cw) / 2;
		buttons.clear();
		idBoxes.clear();
		nameBoxes.clear();
		popup = null;
		popupList = null;
		popupMap = null;
		Measure measure = new Measure() {
			@Override public int width(String s) { return font.width(s); }
			@Override public String fit(String s, int w) { return font.plainSubstrByWidth(s, Math.max(0, w)); }
		};
		layout = EditorLayout.build(new EditorLayout.Inputs(draft, state, open.editable(), cw, height, measure, () -> snapshot, () -> previous,
			held(true), held(false), "by " + open.owner() + "   " + open.x() + " " + open.y() + " " + open.z(), you(), () -> frame));
		for (El el : layout.els()) {
			switch (el.kind) {
				case BUTTON -> addRenderableWidget(Button.builder(label(el), b -> press(el)).pos(left + el.x, el.y).size(el.w, el.h).build()).active = el.active;
				case CHOICE, MENU, CARD -> {
					// No text of its own: the editor draws the label (and an icon, an arrow) over it. It still says it to a narrator.
					Button button = addRenderableWidget(Button.builder(Component.empty(), b -> press(el)).createNarration(text -> Component.literal(el.text))
						.pos(left + el.x, el.y).size(el.w, el.h).build());
					button.active = el.active;
					buttons.put(el, button);
				}
				case NUMBER -> number(el);
				case DECIMAL -> decimal(el);
				case NAME -> text(el);
				default -> { }   // words, lamps, frames and icons are drawn, not widgets
			}
		}
	}

	private static Component label(El el) {
		var text = Component.literal(el.text);
		if (el.color != WHITE) text = text.withStyle(st -> st.withColor(el.color & 0xFFFFFF));   // else the button's own white, or grey when it's off
		return el.sub.isEmpty() ? text : text.append(Component.literal(el.sub).withStyle(ChatFormatting.WHITE));
	}

	/** The side of the block the player stands on, to mark on the map of sides. */
	private Side you() {
		if (minecraft == null || minecraft.player == null) return null;
		double dx = minecraft.player.getX() - (open.x() + 0.5), dz = minecraft.player.getZ() - (open.z() + 0.5);
		return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Side.EAST : Side.WEST) : (dz > 0 ? Side.SOUTH : Side.NORTH);
	}

	private void press(El el) {
		switch (el.kind) {
			case CHOICE, MENU -> { popup = popup == el ? null : el; popupKey = -1; popupList = null; popupMap = null; }
			case CARD -> {
				Presets.Preset preset = Presets.all().get(el.preset);
				use(preset.program(), "the \"" + preset.name() + "\" preset");
			}
			default -> {
				if (el.command != null) { command(el.command); return; }
				if (el.press != null) el.press.run();
				if (el.after == EditorLayout.After.CHANGED) changed();
				else if (el.after == EditorLayout.After.REBUILD) rebuildWidgets();
			}
		}
	}

	private void command(EditorLayout.Command command) {
		switch (command) {
			case SAVE -> save();
			case CLOSE -> onClose();
			case COPY -> copy();
			case PASTE -> paste();
			case PRESETS -> minecraft.gui.setScreen(new PresetScreen(this));
			case LABELS -> dev.whizzle.logic.client.LogicClient.openSettings(this);
		}
	}

	/** Takes an option of the open list. */
	private void choose(int index) {
		El el = popup;
		popup = null;
		if (el.kind == EditorLayout.Kind.MENU) { command(el.commands.get(index)); return; }
		el.choose.accept(index);
		changed();
	}

	private void number(El el) {
		EditBox box = addRenderableWidget(new EditBox(font, left + el.x, el.y, el.w, el.h, Component.literal("number")));
		box.setMaxLength(6);
		box.setValue(el.value);
		box.setResponder(v -> {
			String digits = v.replaceAll("[^0-9]", "");
			if (!digits.equals(v)) { box.setValue(digits); return; }   // numbers only; setValue calls back with the clean text
			el.set.accept(v);
			if (!v.equals(el.value)) dirty = true;
		});
		box.setEditable(el.active);
	}

	/** A box for seconds: digits and one decimal point. */
	private void decimal(El el) {
		EditBox box = addRenderableWidget(new EditBox(font, left + el.x, el.y, el.w, el.h, Component.literal("seconds")));
		box.setMaxLength(7);
		box.setValue(el.value);
		box.setResponder(v -> {
			String clean = v.replaceAll("[^0-9.]", "");
			int dot = clean.indexOf('.');
			if (dot >= 0) clean = clean.substring(0, dot + 1) + clean.substring(dot + 1).replace(".", "");
			if (!clean.equals(v)) { box.setValue(clean); return; }
			el.set.accept(v);
			if (!v.equals(el.value)) dirty = true;
		});
		box.setEditable(el.active);
	}

	/** A box for a block or item name; the list shown while typing in it is {@link NamePicker}'s. */
	private void text(El el) {
		EditBox box = addRenderableWidget(new EditBox(font, left + el.x, el.y, el.w, el.h, Component.literal(el.block ? "block" : "item")));
		box.setMaxLength(IdPattern.MAX);   // names can have several terms
		box.setValue(el.value);
		IdCompletion ids = ids(el.block);
		box.setSuggestion(el.active ? ids.suggestion(el.value) : null);
		box.setHint(Component.literal("stone, *_ore, #logs").withStyle(EditBox.DEFAULT_HINT_STYLE));   // an example, shown while it's empty
		box.addFormatter((shown, from) -> styled(box.getValue(), shown, from, el.block));
		box.setResponder(v -> {
			el.set.accept(v.trim().toLowerCase(Locale.ROOT));
			if (!v.equals(el.value)) dirty = true;
			box.setSuggestion(ids.suggestion(v));   // grey rest of the id; Tab accepts it
		});
		box.setEditable(el.active);
		if (el.active) idBoxes.put(box, ids);
		nameBoxes.add(new NameBox(box, el.block, el.active, el.side, el));
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

	private String hereTitle(boolean block, Side side) {
		if (block) return side == Side.ANY ? "Around this block now" : side.label() + " side now";
		return "In the " + snapshot.block(side) + " (" + side.label().toLowerCase(Locale.ROOT) + ")";
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

	// ---- keys, clicks, the wheel ----

	private static boolean key(int k, int a, int b) { return k == a || k == b; }

	/**
	 * With a list of options open: the arrow keys choose one, Enter takes it, Esc puts the list away. With the list
	 * open under a name: the same, and Tab takes the completion when no row was chosen. With the matches of a name
	 * showing: Delete leaves the chosen one out. Otherwise Tab in a name box accepts its grey suggestion, or moves
	 * between boxes as usual.
	 */
	@Override
	public boolean keyPressed(net.minecraft.client.input.KeyEvent key) {
		int k = key.key();
		final int up = com.mojang.blaze3d.platform.InputConstants.KEY_UP, down = com.mojang.blaze3d.platform.InputConstants.KEY_DOWN,
			enter = com.mojang.blaze3d.platform.InputConstants.KEY_RETURN, pad = com.mojang.blaze3d.platform.InputConstants.KEY_NUMPADENTER,
			esc = com.mojang.blaze3d.platform.InputConstants.KEY_ESCAPE, tab = com.mojang.blaze3d.platform.InputConstants.KEY_TAB;
		if (popup != null) {
			List<EditorLayout.Option> options = popup.options.get();
			if (k == esc) { popup = null; return true; }
			if (key(k, up, down)) {
				int at = popupKey >= 0 ? popupKey : popup.current >= 0 ? popup.current : k == down ? -1 : options.size();
				for (int n = 0; n < options.size(); n++) {
					at = Math.floorMod(at + (k == down ? 1 : -1), options.size());
					if (options.get(at).enabled()) { popupKey = at; break; }
				}
				return true;
			}
			if (key(k, enter, pad) && popupKey >= 0 && popupKey < options.size() && options.get(popupKey).enabled()) { choose(popupKey); return true; }
		}
		if (k == com.mojang.blaze3d.platform.InputConstants.KEY_DELETE && matchesShown != null && matchesShown.count() > 0 && matchesBox != null && matchesCanLeaveOut) {
			leaveOutChosen();
			return true;
		}
		if (pickerLayout != null && pickerView != null && getFocused() == pickerBox) {
			List<NamePicker.Row> rows = pickerView.rows();
			if (k == esc) { pickerClosed = true; pickerLayout = null; return true; }
			if (key(k, up, down)) {
				int step = k == down ? 1 : -1;
				int at = pickIndex >= 0 ? pickIndex : pickerView.tabRow() >= 0 ? pickerView.tabRow() : step > 0 ? -1 : rows.size();
				for (int n = 0; n < rows.size(); n++) {
					at = Math.floorMod(at + step, rows.size());
					if (rows.get(at).value() != null) { pickIndex = at; break; }
				}
				return true;
			}
			boolean isEnter = key(k, enter, pad);
			int row = pickIndex >= 0 ? pickIndex : isEnter ? -1 : pickerView.tabRow();
			if ((isEnter || k == tab) && row >= 0 && row < rows.size() && rows.get(row).value() != null) {
				pick(rows.get(row));
				return true;
			}
		}
		if (k == tab && getFocused() instanceof EditBox box && idBoxes.containsKey(box)) {
			String full = idBoxes.get(box).complete(box.getValue());
			if (full != null) {
				box.setValue(full);
				box.moveCursorToEnd(false);
				return true;
			}
		}
		return super.keyPressed(key);
	}

	/**
	 * A click on an open list takes that option (or row); elsewhere on the list it does nothing, and doesn't reach what's
	 * under it. A click anywhere else puts an open list of options away.
	 */
	@Override
	public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
		if (popup != null) {
			boolean inside = popupMap != null ? popupMap.contains(event.x(), event.y()) : popupList != null && popupList.contains(event.x(), event.y());
			if (inside) {
				int index = popupMap != null ? popupMap.indexAt(event.x(), event.y()) : popupList.indexAt(event.x(), event.y());
				if (index >= 0 && popupOptions != null && index < popupOptions.size() && popupOptions.get(index).enabled()) choose(index);
				return true;
			}
			Button opener = buttons.get(popup);
			popup = null;
			if (opener != null && opener.isMouseOver(event.x(), event.y())) return true;   // its own button again: just close
		}
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

	/**
	 * The wheel goes through what a pattern or tag matches while that panel shows. Otherwise, when the rule is taller
	 * than the screen, it scrolls the rule; when it isn't, it steps the option of the button under the pointer.
	 */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY == 0) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		if (matchesShown != null && matchesShown.count() > 1) {
			matchesChosen = Math.floorMod(matchesChosen + (scrollY < 0 ? 1 : -1), matchesShown.count());
			return true;
		}
		if (popup != null || (pickerLayout != null && pickerLayout.contains(mouseX, mouseY))) return true;
		if (layout.maxScroll() > 0) {
			if (mouseX - left >= layout.x0()) {
				state.scroll = Math.clamp(state.scroll + (scrollY < 0 ? 22 : -22), 0, layout.maxScroll());
				rebuildWidgets();
			}
			return true;
		}
		for (var entry : buttons.entrySet()) {
			El el = entry.getKey();
			if (el.kind != EditorLayout.Kind.CHOICE || !el.active || !entry.getValue().isMouseOver(mouseX, mouseY)) continue;
			int size = el.options.get().size();
			if (size < 2) return true;
			el.choose.accept(Math.floorMod(el.current + (scrollY < 0 ? 1 : -1), size));
			changed();
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
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
		java.util.Map<String, Integer> here = Readings.here(name.block(), name.side(), snapshot);
		if (pickerView == null || !text.equals(pickerText) || cursor != pickerCursor || !java.util.Objects.equals(here, pickerHere)) {
			if (pickerText != null && !text.equals(pickerText)) pickerClosed = false;   // typing brings it back
			if (!text.equals(pickerText) || cursor != pickerCursor) pickIndex = -1;
			pickerText = text;
			pickerCursor = cursor;
			pickerHere = here == null ? null : new java.util.HashMap<>(here);
			pickerView = NamePicker.view(text, cursor, name.block(), ids(name.block()), here, hereTitle(name.block(), name.side()));
			if (pickIndex >= pickerView.rows().size()) pickIndex = -1;
		}
		pickerLayout = pickerClosed || popup != null ? null : NamePanel.layout(p, pickerView, box.getX(), box.getY(), box.getHeight(), width, height);
	}

	// ---- drawing ----

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractRenderState(g, mouseX, mouseY, delta);
		drawOverlays(g, mouseX, mouseY);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
		super.extractBackground(g, mouseX, mouseY, delta);
		if (left > 0) g.fill(left - 6, 0, left + cw + 6, height, 0x50000000);   // the column, on wide screens
	}

	/** The icons under a name: what the box's text matches right now. */
	private NamePicker.Strip strip(El strip) {
		for (NameBox n : nameBoxes) if (n.el() == strip.name) return NamePicker.strip(n.box().getValue(), n.block(), ids(n.block()));
		return null;
	}

	/**
	 * Everything the editor draws itself, in layers over the game's widgets: first its words, lamps, labels and icons
	 * and the names as chips; then whichever list is open; then what's shown for the thing under the pointer (what a
	 * name matches, what a lamp sees).
	 */
	private void drawOverlays(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		Painter p = new GuiPainter(g, font);
		frame++;
		updatePicker(p);
		g.nextStratum();
		EditorPaint.draw(p, layout.els(), left, this::strip);
		if (!status.isEmpty()) p.text(p.fit(status, cw - layout.statusX() - 6), left + layout.statusX(), layout.statusY(), statusColor);
		else if (dirty) p.text("Not saved yet", left + layout.statusX(), layout.statusY(), GOLD);
		for (NameBox n : nameBoxes) {
			if (n.box() == getFocused()) continue;
			String text = n.box().getValue();
			List<NameText.Term> terms = NameText.terms(text);
			if (terms.isEmpty() || !IdPattern.isPattern(text)) continue;   // a plain name stays plain text
			List<Boolean> ok = terms.stream().map(t -> NamePicker.termOk(t, n.block(), ids(n.block()))).toList();
			NamePanel.chips(p, terms, ok, n.box().getX(), n.box().getY(), n.box().getWidth(), n.box().getHeight(), !n.edit());
		}
		matchesShown = null;
		if (popup != null) {
			matchesKey = null;
			matchesBox = null;
			Button opener = buttons.get(popup);
			if (opener == null) { popup = null; return; }
			g.nextStratum();
			popupOptions = popup.options.get();
			if (popup.map && SideMap.width() <= width - 8) {
				popupList = null;
				popupMap = SideMap.layout(popup.sides, opener.getX(), opener.getY(), opener.getHeight(), width, height);
				int hot = popupMap.indexAt(mouseX, mouseY);
				SideMap.draw(p, popup.sides, popupOptions, popupMap, popup.current, hot >= 0 ? hot : popupKey, you());
			} else {
				popupMap = null;
				popupList = OptionPanel.layout(p, popupOptions, opener.getX(), opener.getY(), opener.getHeight(), width, height);
				int hot = popupList.indexAt(mouseX, mouseY);
				OptionPanel.draw(p, popupOptions, popupList, popup.current, hot >= 0 ? hot : popupKey);
			}
			return;
		}
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
			if (pickerLayout != null && n.box() == pickerBox) continue;
			// The pointer on the name, or on the icons under it.
			boolean over = n.box().isMouseOver(mouseX, mouseY);
			for (El el : layout.els()) if (el.kind == EditorLayout.Kind.STRIP && el.name == n.el() && el.contains(mouseX - left, mouseY)) over = true;
			if (!over) continue;
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
		El lamp = EditorPaint.lampAt(layout.els(), left, mouseX, mouseY);
		if (lamp != null) {
			g.nextStratum();
			EditorPaint.tip(p, lamp.lamp.get(), mouseX, mouseY, width, height);
		}
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

	private void changed() { dirty = true; rebuildWidgets(); }

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
		state.selected = 0;
		state.selectedAlso = 0;
		state.scroll = 0;
		state.alsoTab = draft.rules.isEmpty() && !draft.also.isEmpty();
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
}
