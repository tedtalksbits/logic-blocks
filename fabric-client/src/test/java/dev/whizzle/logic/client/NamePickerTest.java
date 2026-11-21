package dev.whizzle.logic.client;

import dev.whizzle.logic.client.ui.IdCompletion;
import dev.whizzle.logic.client.ui.NamePanel;
import dev.whizzle.logic.client.ui.NamePicker;
import dev.whizzle.logic.client.ui.NameText;
import dev.whizzle.logic.client.ui.Painter;
import dev.whizzle.logic.model.IdPattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NamePickerTest {
    private static final List<String> IDS = List.of("stone", "stone_bricks", "cobblestone", "mossy_cobblestone", "oak_log", "birch_log",
        "oak_planks", "deepslate", "deepslate_bricks", "deepslate_tiles", "cobbled_deepslate", "iron_ore", "gold_ore", "diamond");
    private final IdCompletion ids = new IdCompletion(IDS.stream().map(s -> "minecraft:" + s).toList(), List.of("minecraft:logs", "minecraft:logs_that_burn", "minecraft:slabs"));
    private IdPattern.Tags before;

    /** Text is 6 wide a character; records what's drawn. */
    private static final class Fake implements Painter {
        final List<String> texts = new ArrayList<>();
        final List<int[]> fills = new ArrayList<>();
        public void fill(int x1, int y1, int x2, int y2, int argb) { fills.add(new int[] {x1, y1, x2, y2}); }
        public void text(String s, int x, int y, int argb) { texts.add(s); }
        public void plain(String s, int x, int y, int argb) { texts.add(s); }
        public int width(String s) { return s.length() * 6; }
        public String fit(String s, int w) { return s.length() * 6 <= w ? s : s.substring(0, Math.max(0, w / 6)); }
        public void icon(String id, boolean block, int x, int y) {}
    }

    @BeforeEach void tags() {
        before = IdPattern.tags;
        IdPattern.tags = (block, tag, id) -> tag.startsWith("minecraft:logs") && id.endsWith("_log");
        NamePicker.clearCache();
    }

    @AfterEach void restore() { IdPattern.tags = before; }

    private static Map<String, Integer> chest(Object... pairs) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], (Integer) pairs[i + 1]);
        return m;
    }

    @Test void termsAndTyping() {
        List<NameText.Term> terms = NameText.terms("*stone*,  !*Cobblestone* , #logs,oak_planks");
        assertEquals(List.of(NameText.Kind.PATTERN, NameText.Kind.EXCLUDE, NameText.Kind.TAG, NameText.Kind.PLAIN), terms.stream().map(NameText.Term::kind).toList());
        assertEquals("*cobblestone*", terms.get(1).label(), "an exclusion is shown without its !");
        assertEquals(0, terms.get(0).start());
        assertEquals(7, terms.get(0).end());
        assertEquals("*stone*, !*cobblestone*, #logs, oak_planks", NameText.tidy("*stone*,  !*Cobblestone* , #logs,oak_planks"));
        assertEquals("*stone*, #logs", NameText.withoutExclusions("*stone*, !*cobblestone*, #logs"));
        assertEquals("", NameText.withoutExclusions("!oak_log"));
        assertTrue(NameText.terms("stone, ").size() == 1, "a trailing comma adds no term");

        NameText.Typing t = NameText.typing("*stone*, !cobb");
        assertEquals("*stone*, !", t.head());
        assertEquals("cobb", t.body());
        assertFalse(t.tag());
        assertTrue(NameText.typing("stone, #lo").tag());
        assertEquals("", NameText.typing("stone, ").body());
        assertEquals("*stone*, !cobblestone", NameText.replaceLast("*stone*, !cobb", "cobblestone"));
        assertEquals("stone, #logs", NameText.replaceLast("stone, #lo", "#logs"));
        assertEquals("deepslate_*", NameText.replaceLast("deepslate_bricks", "deepslate_*"));
    }

    @Test void completesTheTermBeingTyped() {
        NamePicker.View v = NamePicker.view("sto", false, ids, null, "");
        assertEquals("Items", v.sections().get(0).title());
        assertEquals(List.of("stone", "stone_bricks", "cobblestone", "mossy_cobblestone"), v.rows().stream().map(NamePicker.Row::label).toList(),
            "names starting with it first, then those with it anywhere");
        assertEquals(0, v.tabRow());
        assertEquals("stone", ids.complete("sto"), "Tab's row is what the grey suggestion shows");

        v = NamePicker.view("stone", false, ids, null, "");
        assertEquals(-1, v.tabRow(), "a whole name: Tab moves on");

        v = NamePicker.view("stone, #lo", true, ids, null, "");
        assertEquals("Tags", v.sections().get(0).title());
        assertEquals("#logs", v.rows().get(0).value());
        assertEquals("oak_log", v.rows().get(0).icon(), "a tag shows its first member");
        assertTrue(v.rows().get(0).note().contains("2 blocks"), v.rows().get(0).note());
        assertTrue(v.rows().get(0).special());

        v = NamePicker.view("zzz", false, ids, null, "");
        assertTrue(v.rows().isEmpty());
        assertEquals("Nothing called \"zzz\"", v.sections().get(0).title());

        v = NamePicker.view("*_ore", false, ids, null, "");
        assertEquals("\"*_ore\" matches 2", v.sections().get(0).title());
        assertNull(v.rows().get(0).value(), "what a wildcard matches is shown, not chosen");

        assertTrue(NamePicker.view("", false, ids, null, "").sections().isEmpty(), "an empty box: only the symbols line");
    }

    @Test void showsWhatIsThereAndWhetherItFits() {
        Map<String, Integer> here = chest("stone", 64, "stone_bricks", 12, "cobblestone", 41, "oak_log", 3);
        NamePicker.View v = NamePicker.view("*stone*, !*cobblestone*", false, ids, here, "In the chest (up)");
        NamePicker.Section there = v.sections().stream().filter(s -> s.title().equals("In the chest (up)")).findFirst().orElseThrow();
        assertEquals(List.of("stone", "cobblestone", "stone_bricks", "oak_log"), there.rows().stream().map(NamePicker.Row::label).toList(), "most plentiful first");
        assertEquals(List.of(NamePicker.State.MATCH, NamePicker.State.LEFT_OUT, NamePicker.State.MATCH, NamePicker.State.NO_MATCH),
            there.rows().stream().map(NamePicker.Row::state).toList());
        assertEquals("41   left out", there.rows().get(1).note());

        v = NamePicker.view("*stone*, !", false, ids, here, "In the chest (up)");
        assertEquals(NamePicker.State.MATCH, v.rows().get(1).state(), "an unfinished last term is ignored: cobblestone still fits *stone*");

        v = NamePicker.view("", false, ids, here, "In the chest (up)");
        assertEquals(NamePicker.State.NONE, v.rows().get(0).state());
        assertEquals("64", v.rows().get(0).note());

        v = NamePicker.view("air", true, ids, chest("deepslate", -1), "North side");
        assertEquals("", v.rows().get(v.rows().size() - 1).note(), "a block has no count, and one that doesn't fit no words");
        assertEquals(NamePicker.State.NO_MATCH, v.rows().get(v.rows().size() - 1).state());
    }

    @Test void offersAPatternForSeveralKinds() {
        assertEquals(new NamePicker.Offer("deepslate_*", 2), NamePicker.offer(chest("deepslate_bricks", 5, "deepslate_tiles", 2, "stone", 9), "deepslate_bricks", false));
        assertEquals(new NamePicker.Offer("*deepslate*", 3), NamePicker.offer(chest("deepslate_bricks", 5, "cobbled_deepslate", 2, "deepslate", 9), "deepslate_bricks", false));
        assertEquals(new NamePicker.Offer("*_ore", 2), NamePicker.offer(chest("iron_ore", 5, "gold_ore", 2, "stone", 1), "", false),
            "nothing typed: from the most plentiful kind");
        assertEquals(new NamePicker.Offer("stone*", 2), NamePicker.offer(chest("stone", 5, "stone_bricks", 2, "diamond", 1), "stone", false));
        assertNull(NamePicker.offer(chest("stone", 5, "diamond", 2), "stone", false), "one kind: nothing to offer");
        assertNull(NamePicker.offer(chest("iron_ore", 5, "gold_ore", 2), "*_ore", false), "already a pattern");
        assertNull(NamePicker.offer(chest("iron_ore", 5), "iron_ore", false));
        assertEquals(new NamePicker.Offer("*_ore", 2), NamePicker.offer(chest("iron_ore", 5, "gold_ore", 2), "red_wool", false),
            "a name that isn't there (the default, a half-typed one): from the most plentiful kind");
        assertNull(NamePicker.offer(chest("iron_ore", 5, "gold_ore", 2, "stone", 3), "stone", false), "a name that is there and shares nothing: no offer");

        NamePicker.View v = NamePicker.view("deepslate_bricks", false, ids, chest("deepslate_bricks", 5, "deepslate_tiles", 2), "In the chest (up)");
        NamePicker.Row offer = v.rows().get(v.rows().size() - 1);
        assertEquals("deepslate_*", offer.value());
        assertEquals("2 kinds here", offer.note());
        v = NamePicker.view("deepslate_*, #lo", false, ids, chest("deepslate_bricks", 5, "deepslate_tiles", 2), "In the chest (up)");
        assertTrue(v.sections().stream().noneMatch(sec -> sec.title().equals("Try")), "nothing to try when the name already takes those");
    }

    @Test void summaryOfAName() {
        NamePicker.Summary s = NamePicker.summary("*stone*, !*cobblestone*", false, ids);
        assertEquals(2, s.count());
        assertEquals(List.of("stone", "stone_bricks"), s.all());
        assertEquals(2, s.leftOutCount());
        assertEquals(List.of("cobblestone", "mossy_cobblestone"), s.leftOut());
        assertEquals(0, NamePicker.summary("nothing_*", false, ids).count());
        assertNull(NamePicker.summary("stone", false, ids), "a plain name has nothing to sum up");
        assertNull(NamePicker.summary("stone, ", false, ids));
        assertEquals(0, NamePicker.summary("#logs", false, ids).leftOutCount());
    }

    @Test void matchesPanelNamesTheChosenOne() {
        Fake p = new Fake();
        NamePicker.Summary s = NamePicker.summary("*", false, ids);
        assertEquals(IDS.size(), s.count());
        int[] size = NamePanel.hoverSize(p, s, false);
        NamePanel.hover(p, s, false, 10, 10, 0);
        assertTrue(p.texts.contains(s.all().get(0)) && p.texts.contains("1 of 14   scroll to browse"), p.texts.toString());
        p.texts.clear();
        NamePanel.hover(p, s, false, 10, 10, 13);
        assertTrue(p.texts.contains(s.all().get(13)) && p.texts.contains("14 of 14   scroll to browse"), "the wheel reaches the last, past the first row");
        assertArrayEquals(size, NamePanel.hoverSize(p, s, false), "the panel doesn't change size while scrolling");
        for (int[] f : p.fills) assertTrue(f[0] >= 10 && f[1] >= 10 && f[2] <= 10 + size[0] && f[3] <= 10 + size[1], "everything inside the panel");

        p.texts.clear();
        NamePanel.hover(p, NamePicker.summary("#logs", false, ids), false, 10, 10, 1);
        assertTrue(p.texts.contains("Matches 2 items") && p.texts.contains("birch_log"), "a tag: its members, the second chosen: " + p.texts);
        p.texts.clear();
        NamePanel.hover(p, NamePicker.summary("nothing_*", false, ids), false, 10, 10, 0);
        assertEquals(List.of("Matches nothing"), p.texts);

        assertArrayEquals(new int[] {30, 32}, NamePanel.byPointer(20, 20, 100, 50, 480, 270));
        assertTrue(NamePanel.byPointer(470, 260, 100, 50, 480, 270)[0] + 100 <= 480, "kept on screen");
        NamePanel.Layout l = new NamePanel.Layout(100, 40, 200, 100, List.of(), List.of());
        assertArrayEquals(new int[] {302, 60}, NamePanel.beside(l, 60, 120, 50, 480, 270), "right of the list, level with the row");
        assertTrue(NamePanel.beside(l, 60, 200, 50, 480, 270)[0] + 200 <= 480, "or where it fits");
    }

    @Test void whichTermsStandForSomething() {
        List<NameText.Term> terms = NameText.terms("stone, nope, *_ore, zz*, #logs, #nope, !cobblestone");
        assertEquals(List.of(true, false, true, false, true, false, true), terms.stream().map(t -> NamePicker.termOk(t, false, ids)).toList());
    }

    @Test void theListStaysOnScreen() {
        Fake p = new Fake();
        NamePicker.View v = NamePicker.view("sto", false, ids, chest("stone", 64, "cobblestone", 41), "In the chest (up)");
        NamePanel.Layout l = NamePanel.layout(p, v, 300, 60, 18, 640, 270);
        assertEquals(300, l.x());
        assertEquals(80, l.y(), "under the box");
        assertEquals(v.rows().stream().filter(r -> r.value() != null).count(), l.hits().size());
        assertEquals(1, l.legend().size());
        assertEquals(0, l.rowAt(l.x() + 10, l.hits().get(0).y() + 2));
        assertEquals(-1, l.rowAt(l.x() + 10, l.y() + 1), "a heading isn't a row");

        l = NamePanel.layout(p, v, 300, 240, 18, 480, 270);
        assertTrue(l.y() + l.h() <= 240, "no room below: above the box");
        l = NamePanel.layout(p, v, 460, 60, 18, 480, 270);
        assertTrue(l.x() + l.w() <= 480 - 4, "pushed left to fit");
        l = NamePanel.layout(p, v, 10, 60, 18, 200, 270);
        assertTrue(l.w() <= 192 && l.legend().size() > 1, "a narrow screen wraps the symbols line");
        NamePanel.draw(p, v, l, false, 0);
        assertTrue(p.texts.contains("any letters") && p.texts.contains("and also"));
        for (NamePanel.Hit h : l.hits()) assertTrue(h.y() + h.h() <= l.y() + l.h());
    }

    @Test void chipsThatDoNotFitAreCounted() {
        Fake p = new Fake();
        List<NameText.Term> terms = NameText.terms("*stone*, !*cobblestone*, #logs");
        NamePanel.chips(p, terms, List.of(true, true, true), 0, 0, 300, 18, false);
        assertEquals(List.of("*stone*", "*cobblestone*", "#logs"), p.texts);
        p.texts.clear();
        p.fills.clear();
        NamePanel.chips(p, terms, List.of(true, true, true), 0, 0, 110, 18, false);
        assertEquals(List.of("*stone*", "+2"), p.texts);
        for (int[] f : p.fills) assertTrue(f[2] <= 110, "nothing drawn past the box");
    }
}
