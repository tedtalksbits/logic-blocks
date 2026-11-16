package dev.whizzle.logic.model;

/**
 * Block and item names with wildcards: {@code *} stands for any run of characters, so {@code deepslate_*} is every
 * id starting "deepslate_", {@code *_ore} every ore, {@code *deepslate*} anything with "deepslate" in it, and a
 * plain {@code *} anything. Used by Block and Items conditions and Move actions. Pure Java, shared.
 */
public final class IdPattern {
    private IdPattern() {}

    public static boolean isPattern(String id) { return id.indexOf('*') >= 0; }

    /** A pattern players may write: the id characters plus {@code *}, at most {@link Condition#MAX_ID} long. */
    public static boolean valid(String pattern) {
        return !pattern.isEmpty() && pattern.length() <= Condition.MAX_ID && pattern.matches("[a-z0-9_./*-]+");
    }

    /** Whether {@code id} is {@code pattern} (no {@code *}) or fits it. */
    public static boolean matches(String pattern, String id) {
        if (!isPattern(pattern)) return pattern.equals(id);
        // Glob match: '*' any run (including none). Iterative, backtracking to the last star.
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

    /** Total of the counts whose item id fits {@code pattern}. */
    public static int count(java.util.Map<String, Integer> counts, String pattern) {
        if (!isPattern(pattern)) return counts.getOrDefault(pattern, 0);
        int n = 0;
        for (var e : counts.entrySet()) if (matches(pattern, e.getKey())) n += e.getValue();
        return n;
    }
}
