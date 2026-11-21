package dev.whizzle.logic.client;

import dev.whizzle.logic.client.ui.Draft;
import dev.whizzle.logic.client.ui.EditorLayout;
import dev.whizzle.logic.client.ui.EditorLayout.El;
import dev.whizzle.logic.client.ui.EditorLayout.Kind;
import dev.whizzle.logic.client.ui.EditorPaint;
import dev.whizzle.logic.client.ui.Measure;
import dev.whizzle.logic.client.ui.OptionPanel;
import dev.whizzle.logic.client.ui.Painter;
import dev.whizzle.logic.client.ui.SideMap;
import dev.whizzle.logic.model.Action;
import dev.whizzle.logic.model.Condition;
import dev.whizzle.logic.model.Presets;
import dev.whizzle.logic.model.Side;
import dev.whizzle.logic.model.Snapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The editor's layout, checked without the game: nothing overlaps or leaves the screen, at the sizes players really have. */
class EditorLayoutTest {
    /** Wider than the game's font (about 5.5 a letter), so a layout that fits here fits there. */
    static final Measure SIX = new Measure() {
        public int width(String s) { return s.length() * 6; }
        public String fit(String s, int w) { return s.length() * 6 <= w ? s : s.substring(0, Math.max(0, w / 6)); }
    };

    static Snapshot snapshot() {
        String[] blocks = {"deepslate_tiles", "chest", "air", "redstone_wire", "chest", "hopper"};
        int[] signals = {0, 0, 0, 12, 0, 0};
        List<Map<String, Integer>> containers = new ArrayList<>(Arrays.asList(null, Map.of("stone", 64, "cobblestone", 41, "stone_bricks", 12), null, null,
            Map.of("deepslate_bricks", 30, "deepslate_tiles", 12, "cobbled_deepslate", 9), Map.of()));
        return new Snapshot(blocks, signals, containers, new int[0], 15, 0);
    }

    static EditorLayout.Result build(Draft draft, EditorLayout.State state, boolean edit, int cw, int height) {
        Snapshot s = snapshot();
        return EditorLayout.build(new EditorLayout.Inputs(draft, state, edit, cw, height, SIX, () -> s, () -> null, "oak_planks", "diamond", "by Ted   10 64 -3", Side.SOUTH, new java.util.concurrent.atomic.AtomicLong()::incrementAndGet));
    }

    /** A rule with three conditions of one kind and, as extras, the given action kinds. */
    static Draft.Rule rule(Condition.Kind kind, boolean output, Action.Kind... extras) {
        Draft.Rule r = output ? new Draft.Rule() : Draft.Rule.also();
        r.conditions.clear();
        r.extras.clear();
        for (int i = 0; i < 3; i++) { Draft.Cond c = new Draft.Cond(); c.setKind(kind); r.conditions.add(c); }
        for (Action.Kind k : extras) { Draft.Extra e = new Draft.Extra(); e.setKind(k); r.extras.add(e); }
        if (output) r.action = Action.Kind.PULSE;
        return r;
    }

    static boolean solid(El el) { return el.kind != Kind.LAMP && el.kind != Kind.FRAME; }

    static void assertSound(EditorLayout.Result r, int cw, int height, String what) {
        List<El> els = r.els();
        for (El el : els) {
            assertTrue(el.x >= 0 && el.x + el.w <= cw, what + ": off the side: " + el);
            assertTrue(el.y >= 0 && el.y + el.h <= height, what + ": off the top or bottom: " + el);
        }
        for (int i = 0; i < els.size(); i++) for (int j = i + 1; j < els.size(); j++) {
            El a = els.get(i), b = els.get(j);
            if (!solid(a) || !solid(b)) continue;
            boolean apart = a.x + a.w <= b.x || b.x + b.w <= a.x || a.y + a.h <= b.y || b.y + b.h <= a.y;
            assertTrue(apart, what + ": these overlap: " + a + " and " + b);
        }
    }

    @Test void nothingOverlapsOrLeavesTheScreen() {
        int[][] sizes = {{480, 270}, {640, 360}, {854, 480}, {960, 540}, {1080, 600}};
        for (int[] size : sizes) for (boolean edit : new boolean[] {true, false}) {
            for (Condition.Kind kind : Condition.Kind.values()) {
                Draft d = new Draft();
                d.rules.add(rule(kind, true, Action.Kind.MOVE, Action.Kind.START));
                d.also.add(rule(kind, false, Action.Kind.MOVE, Action.Kind.ADD, Action.Kind.SET_COUNTER));
                for (boolean also : new boolean[] {false, true}) {
                    EditorLayout.State st = new EditorLayout.State();
                    st.alsoTab = also;
                    String what = kind + (also ? " when" : " if/else") + (edit ? "" : " view-only") + " at " + size[0] + "x" + size[1];
                    EditorLayout.Result r = build(d, st, edit, size[0], size[1]);
                    assertSound(r, size[0], size[1], what);
                    // and scrolled to the end
                    st.scroll = r.maxScroll();
                    assertSound(build(d, st, edit, size[0], size[1]), size[0], size[1], what + " scrolled");
                }
            }
            Draft full = new Draft();
            for (int i = 0; i < 8; i++) full.rules.add(rule(Condition.Kind.ITEMS, true, Action.Kind.SUBTRACT, Action.Kind.STOP));
            assertSound(build(full, new EditorLayout.State(), edit, size[0], size[1]), size[0], size[1], "eight rules at " + size[0]);
            assertSound(build(new Draft(), new EditorLayout.State(), edit, size[0], size[1]), size[0], size[1], "a new block at " + size[0]);
        }
    }

    @Test void aTallRuleScrolls() {
        Draft d = new Draft();
        d.rules.add(rule(Condition.Kind.ITEMS, true, Action.Kind.MOVE, Action.Kind.MOVE));
        EditorLayout.State st = new EditorLayout.State();
        EditorLayout.Result r = build(d, st, true, 480, 270);
        assertTrue(r.maxScroll() > 0, "three name rows, a Then row and two moves don't fit 270 high");
        assertTrue(r.els().stream().noneMatch(el -> el.text.equals("+ Action")), "the end is out of view");
        assertTrue(r.els().stream().anyMatch(el -> el.text.equals("scroll for the rest")));
        st.scroll = 10_000;
        r = build(d, st, true, 480, 270);
        assertEquals(r.maxScroll(), st.scroll, "scrolling stops at the end");
        assertTrue(r.els().stream().anyMatch(el -> el.text.equals("+ Action")));
        assertTrue(r.els().stream().anyMatch(el -> el.text.equals("scroll back up")));
        // A row is in view whole or not at all: no shortcut buttons left over from a row whose name has scrolled away.
        for (int scroll = 0; scroll <= r.maxScroll(); scroll += 7) {
            st.scroll = scroll;
            List<El> els = build(d, st, true, 480, 270).els();
            long names = els.stream().filter(el -> el.kind == Kind.NAME).count(), strips = els.stream().filter(el -> el.kind == Kind.STRIP).count();
            assertEquals(names, strips, "at scroll " + scroll);
            for (El el : els) if (el.h == 14) assertTrue(els.stream().anyMatch(n -> n.kind == Kind.NAME && n.y == el.y - 21), "a shortcut without its name at scroll " + scroll + ": " + el);
        }
        assertEquals(0, build(d, new EditorLayout.State(), true, 960, 540).maxScroll(), "a bigger screen shows it all");
    }

    @Test void choicesListEveryOptionAndChangeTheDraft() {
        Draft d = new Draft();
        d.rules.add(new Draft.Rule());
        EditorLayout.Result r = build(d, new EditorLayout.State(), true, 960, 540);
        List<El> choices = r.els().stream().filter(el -> el.kind == Kind.CHOICE && el.y > 40 && el.y < 60).toList();
        El kind = choices.get(0), side = choices.get(1), op = choices.get(2);
        assertEquals("Block", kind.text);
        assertEquals(Condition.Kind.values().length, kind.options.get().size(), "all eight kinds at once");
        assertTrue(kind.options.get().stream().noneMatch(o -> o.note().isEmpty()), "each with a few words");
        assertEquals(0, kind.current);
        assertTrue(side.map, "a side opens the map");
        assertEquals("deepslate_tiles", side.options.get().get(0).note(), "with what's on each side");
        assertEquals("deepslate_tiles", side.icon.get(), "and the button shows what's on the chosen one");
        assertEquals(List.of("is", "is not"), op.options.get().stream().map(EditorLayout.Option::label).toList());

        kind.choose.accept(List.of(Condition.Kind.values()).indexOf(Condition.Kind.ITEMS));
        assertEquals(Condition.Kind.ITEMS, d.rules.get(0).conditions.get(0).kind);
        assertEquals(EditorLayout.After.CHANGED, kind.after);
        side.choose.accept(1);
        assertEquals(Side.EAST, d.rules.get(0).conditions.get(0).side);

        El menu = r.els().stream().filter(el -> el.kind == Kind.MENU).findFirst().orElseThrow();
        assertEquals(List.of("Copy", "Paste", "Presets", "Labels"), menu.options.get().stream().map(EditorLayout.Option::label).toList());
        assertEquals(List.of(EditorLayout.Command.COPY, EditorLayout.Command.PASTE, EditorLayout.Command.PRESETS, EditorLayout.Command.LABELS), menu.commands);

        El viewMenu = build(d, new EditorLayout.State(), false, 960, 540).els().stream().filter(el -> el.kind == Kind.MENU).findFirst().orElseThrow();
        assertFalse(viewMenu.options.get().get(1).enabled(), "someone who can't edit can copy but not paste");
        assertTrue(viewMenu.options.get().get(0).enabled());
    }

    @Test void lampsSayWhatTheBlockSees() {
        Draft d = new Draft();
        Draft.Rule rule = new Draft.Rule();
        rule.conditions.get(0).id = "deepslate_*";
        d.rules.add(rule);
        EditorLayout.Result r = build(d, new EditorLayout.State(), true, 960, 540);
        List<El> lamps = r.els().stream().filter(el -> el.kind == Kind.LAMP).toList();
        assertEquals(3, lamps.size(), "the rule in the list, the rule's title, its one condition");
        for (El lamp : lamps) assertEquals(1, lamp.lamp.get().state(), "north is deepslate_tiles, which the name fits");
        assertTrue(lamps.get(2).lamp.get().reading().startsWith("now: "), lamps.get(2).lamp.get().reading());
        assertEquals("true: sets the output now", lamps.get(0).lamp.get().reading());
        rule.conditions.get(0).id = "stone";
        assertEquals(0, lamps.get(2).lamp.get().state(), "live: the lamp follows the draft without laying out again");
        El title = r.els().stream().filter(el -> el.kind == Kind.WORD && el.live != null && el.live.get().startsWith("Rule 1")).findFirst().orElseThrow();
        assertEquals("Rule 1", title.live.get());
        assertEquals(r.els().indexOf(lamps.get(2)), r.els().indexOf(EditorPaint.lampAt(r.els(), 100, 100 + lamps.get(2).x + 9, lamps.get(2).y - 2)), "a lamp is found a few pixels around it");
    }

    @Test void aNameRowHasItsIconsAndShortcuts() {
        Draft d = new Draft();
        Draft.Rule rule = new Draft.Rule();
        rule.conditions.get(0).setKind(Condition.Kind.ITEMS);
        rule.conditions.get(0).side = Side.UP;
        rule.conditions.get(0).id = "red_wool";
        d.rules.add(rule);
        EditorLayout.Result r = build(d, new EditorLayout.State(), true, 960, 540);
        El name = r.els().stream().filter(el -> el.kind == Kind.NAME).findFirst().orElseThrow();
        El strip = r.els().stream().filter(el -> el.kind == Kind.STRIP).findFirst().orElseThrow();
        assertSame(name, strip.name);
        assertEquals(name.y + 20, strip.y, "the icons sit under the name");
        El offer = r.els().stream().filter(el -> el.text.equals("*deepslate*")).findFirst().orElseThrow();
        assertEquals(" 3 kinds", offer.sub, "the chest above holds three deepslate kinds");
        offer.press.run();
        assertEquals("*deepslate*", rule.conditions.get(0).id);
        El hand = r.els().stream().filter(el -> el.text.equals("hand")).findFirst().orElseThrow();
        hand.press.run();
        assertEquals("diamond", rule.conditions.get(0).id, "what the player holds, as an item");

        List<El> view = build(d, new EditorLayout.State(), false, 960, 540).els();
        assertTrue(view.stream().noneMatch(el -> el.text.equals("hand") || !el.sub.isEmpty()), "no shortcuts on a block you can't edit");
        assertTrue(view.stream().anyMatch(el -> el.kind == Kind.STRIP), "but the icons show");
        assertTrue(view.stream().filter(el -> el.kind == Kind.CHOICE).noneMatch(el -> el.active));
    }

    @Test void aNewBlockStartsWithChoices() {
        Draft d = new Draft();
        EditorLayout.State st = new EditorLayout.State();
        EditorLayout.Result r = build(d, st, true, 640, 360);
        List<El> cards = r.els().stream().filter(el -> el.kind == Kind.CARD).toList();
        assertEquals(List.of("Clock", "Sorter station", "Latch"), cards.stream().map(el -> el.text).toList());
        for (El card : cards) assertEquals(card.text, Presets.all().get(card.preset).name());
        El empty = r.els().stream().filter(el -> el.text.equals("+ Start empty")).findFirst().orElseThrow();
        empty.press.run();
        assertEquals(1, d.rules.size());
        assertTrue(build(d, st, true, 640, 360).els().stream().noneMatch(el -> el.kind == Kind.CARD), "gone once there's a rule");
        assertTrue(build(new Draft(), new EditorLayout.State(), false, 640, 360).els().stream().noneMatch(el -> el.kind == Kind.CARD), "not on a block you can't edit");
    }

    @Test void tabsSayHowTheyWork() {
        Draft d = new Draft();
        d.rules.add(new Draft.Rule());
        EditorLayout.State st = new EditorLayout.State();
        String words = String.join(" ", build(d, st, true, 960, 540).els().stream().filter(el -> el.kind == Kind.WORD).map(el -> el.text).toList());
        assertTrue(words.contains("first true rule sets the output"), words);
        El when = build(d, st, true, 960, 540).els().stream().filter(el -> el.text.equals("When (0)")).findFirst().orElseThrow();
        when.press.run();
        assertTrue(st.alsoTab);
        assertEquals(EditorLayout.After.REBUILD, when.after);
        words = String.join(" ", build(d, st, true, 960, 540).els().stream().filter(el -> el.kind == Kind.WORD).map(el -> el.text).toList());
        assertTrue(words.contains("every true rule runs"), words);
        // On a narrow screen the line wraps instead of running into the rules.
        assertSound(build(d, new EditorLayout.State(), true, 480, 270), 480, 270, "narrow");
    }

    /** Records only sizes: the panels' own layout is what's checked. */
    static final class Fake implements Painter {
        public void fill(int x1, int y1, int x2, int y2, int argb) {}
        public void text(String s, int x, int y, int argb) {}
        public void plain(String s, int x, int y, int argb) {}
        public int width(String s) { return s.length() * 6; }
        public String fit(String s, int w) { return SIX.fit(s, w); }
        public void icon(String id, boolean block, int x, int y) {}
    }

    @Test void listsAndTheSideMapStayOnScreen() {
        Fake p = new Fake();
        Draft d = new Draft();
        d.rules.add(rule(Condition.Kind.SIGNAL, true));
        for (int[] size : new int[][] {{480, 270}, {960, 540}}) {
            EditorLayout.Result r = build(d, new EditorLayout.State(), true, size[0], size[1]);
            for (El el : r.els()) {
                if (el.kind != Kind.CHOICE && el.kind != Kind.MENU) continue;
                List<EditorLayout.Option> options = el.options.get();
                OptionPanel.Layout l = OptionPanel.layout(p, options, el.x, el.y, el.h, size[0], size[1]);
                assertTrue(l.x() >= 0 && l.x() + l.w() <= size[0] && l.y() >= 0 && l.y() + l.h() <= size[1], "list of " + el + " at " + size[0]);
                assertTrue(l.y() >= el.y + el.h || l.y() + l.h() <= el.y, "it doesn't cover its own button: " + el);
                assertEquals(0, l.indexAt(l.x() + 5, l.y() + 5));
                assertEquals(options.size() - 1, l.indexAt(l.x() + 5, l.y() + l.h() - 5));
                assertEquals(-1, l.indexAt(l.x() - 1, l.y() + 5));
                if (!el.map) continue;
                SideMap.Layout m = SideMap.layout(el.sides, el.x, el.y, el.h, size[0], size[1]);
                assertTrue(m.x() >= 0 && m.x() + m.w() <= size[0] && m.y() >= 0 && m.y() + m.h() <= size[1], "side map at " + size[0]);
                assertEquals(el.sides.size(), m.cells().size());
                for (int i = 0; i < m.cells().size(); i++) {
                    int[] c = m.cells().get(i);
                    assertEquals(i, m.indexAt(c[0] + 2, c[1] + 2), "each side has its own cell");
                    assertTrue(c[0] >= m.x() && c[0] + c[2] <= m.x() + m.w() && c[1] >= m.y() && c[1] + c[3] <= m.y() + m.h());
                }
                assertEquals(-1, m.indexAt(m.self()[0] + 2, m.self()[1] + 2), "the middle is this block, not a side");
            }
        }
        assertTrue(SideMap.width() <= 480 - 8, "the map fits the smallest usual screen");
    }
}
