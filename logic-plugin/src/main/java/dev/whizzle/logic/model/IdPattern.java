package dev.whizzle.logic.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Block and item names that stand for more than one id. Used by Block and Items conditions and Move actions.
 * <ul>
 *   <li>Wildcards: {@code *} is any run of characters. {@code deepslate_*} is every id starting "deepslate_",
 *       {@code *_ore} every ore, {@code *deepslate*} anything with "deepslate" in it, {@code *} anything.</li>
 *   <li>Tags: {@code #slabs}, {@code #logs}, {@code #minecraft:wool}: Minecraft's own groups (block tags for Block
 *       conditions, item tags for Items and Move). A name without a namespace is {@code minecraft:}.</li>
 *   <li>Several terms, comma-separated: an id fits if it fits any of them...</li>
 *   <li>...and none of the terms starting {@code !}: {@code *stone*, !*cobblestone*} is every stone-ish id but no
 *       cobblestone. Only exclusions ({@code !cobblestone}) means anything but those.</li>
 * </ul>
 * Pure Java, shared by the plugin and the mod; each side gives it a {@link Tags} lookup for its tags.
 */
public final class IdPattern {
    private IdPattern() {}

    /** The longest name: room for several terms (a plain id stays at most {@link Condition#MAX_ID}). */
    public static final int MAX = 128;

    /** Whether the game has {@code id} in tag {@code tag} (a full key, {@code minecraft:slabs}); blocks or items. */
    public interface Tags { boolean has(boolean block, String tag, String id); }

    /** Set by each side at startup: the plugin from Bukkit's tags, the mod from the game's registries. */
    public static volatile Tags tags = (block, tag, id) -> false;

    /** Anything but a plain id: a wildcard, a tag, an exclusion or several terms. */
    public static boolean isPattern(String id) {
        return id.indexOf('*') >= 0 || id.indexOf('#') >= 0 || id.indexOf('!') >= 0 || id.indexOf(',') >= 0;
    }

    /** One term of a name, without its comma, spaces or "!". */
    private record Term(boolean exclude, boolean tag, String text) {}

    /** Parsed names, so matching every check doesn't re-parse (programs hold few distinct names). */
    private static final Map<String, List<Term>> PARSED = new java.util.concurrent.ConcurrentHashMap<>();

    private static List<Term> terms(String pattern) {
        List<Term> cached = PARSED.get(pattern);
        if (cached != null) return cached;
        List<Term> parsed = parse(pattern);
        if (PARSED.size() < 4096) PARSED.put(pattern, parsed);
        return parsed;
    }

    private static List<Term> parse(String pattern) {
        List<Term> out = new ArrayList<>();
        for (String raw : pattern.split(",", -1)) {
            String t = raw.strip();
            boolean exclude = t.startsWith("!");
            if (exclude) t = t.substring(1).strip();
            boolean tag = t.startsWith("#");
            if (tag) { t = t.substring(1); if (t.indexOf(':') < 0) t = "minecraft:" + t; }
            out.add(new Term(exclude, tag, t));
        }
        return List.copyOf(out);
    }

    /** A name players may write: every term a valid id, wildcard or tag, at most {@link #MAX} long. */
    public static boolean valid(String pattern) {
        if (pattern.isBlank() || pattern.length() > MAX) return false;
        for (Term t : terms(pattern)) {
            if (t.text().isEmpty()) return false;
            if (t.tag() ? !t.text().matches("[a-z0-9_.-]+:[a-z0-9_./-]+") : !t.text().matches("[a-z0-9_./*-]+")) return false;
        }
        return true;
    }

    /** Item names: {@link #matches(String, String, boolean)} with item tags. */
    public static boolean matches(String pattern, String id) { return matches(pattern, id, false); }

    /** Whether {@code id} is {@code pattern} (a plain id) or fits it; {@code block}: use block tags, else item tags. */
    public static boolean matches(String pattern, String id, boolean block) {
        if (!isPattern(pattern)) return pattern.equals(id);
        boolean anyInclude = false, included = false;
        for (Term t : terms(pattern)) {
            boolean fits = t.tag() ? tags.has(block, t.text(), id) : glob(t.text(), id);
            if (t.exclude()) { if (fits) return false; }
            else { anyInclude = true; included |= fits; }
        }
        return !anyInclude || included;
    }

    /** '*' is any run of characters (including none). Iterative, backtracking to the last star. */
    static boolean glob(String pattern, String id) {
        int p = 0, s = 0, star = -1, mark = 0;
        while (s < id.length()) {
            if (p < pattern.length() && pattern.charAt(p) == '*') { star = p++; mark = s; }
            else if (p < pattern.length() && pattern.charAt(p) == id.charAt(s)) { p++; s++; }
            else if (star >= 0) { p = star + 1; s = ++mark; }
            else return false;
        }
        while (p < pattern.length() && pattern.charAt(p) == '*') p++;
        return p == pattern.length();
    }

    /** Total of the item counts whose id fits {@code pattern}. */
    public static int count(Map<String, Integer> counts, String pattern) {
        if (!isPattern(pattern)) return counts.getOrDefault(pattern, 0);
        int n = 0;
        for (var e : counts.entrySet()) if (matches(pattern, e.getKey(), false)) n += e.getValue();
        return n;
    }

    /** The tags a name mentions, as full keys (for the editor's checks). */
    public static List<String> tagsIn(String pattern) {
        List<String> out = new ArrayList<>();
        for (Term t : terms(pattern)) if (t.tag()) out.add(t.text());
        return out;
    }
}
