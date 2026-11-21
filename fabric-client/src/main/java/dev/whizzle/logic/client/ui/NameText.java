package dev.whizzle.logic.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A block or item name as typed in the editor ("*stone*, !*cobblestone*, #logs"), taken apart: its terms and where
 * each sits in the text, and the term being typed (pure Java, unit-tested). What a name matches is
 * {@link dev.whizzle.logic.model.IdPattern}'s business; this is only for showing and editing it.
 */
public final class NameText {
	private NameText() {}

	public enum Kind { PLAIN, PATTERN, TAG, EXCLUDE }

	/** One term: where it sits in the text (without the spaces around it) and its text, lower case, "!" or "#" included. */
	public record Term(int start, int end, Kind kind, String text) {
		/** The text to show for it: without the "!" of an exclusion (those are shown struck through instead). */
		public String label() { return kind == Kind.EXCLUDE ? text.substring(1).strip() : text; }
	}

	public static List<Term> terms(String text) {
		List<Term> out = new ArrayList<>();
		int from = 0;
		while (from <= text.length()) {
			int comma = text.indexOf(',', from);
			int s = from, e = comma < 0 ? text.length() : comma;
			while (s < e && text.charAt(s) == ' ') s++;
			while (e > s && text.charAt(e - 1) == ' ') e--;
			if (e > s) {
				String t = text.substring(s, e).toLowerCase(Locale.ROOT);
				Kind kind = t.startsWith("!") ? Kind.EXCLUDE : t.startsWith("#") ? Kind.TAG : t.indexOf('*') >= 0 ? Kind.PATTERN : Kind.PLAIN;
				out.add(new Term(s, e, kind, t));
			}
			if (comma < 0) break;
			from = comma + 1;
		}
		return out;
	}

	/** The name with its terms tidied ("a,  !b ," -> "a, !b"), as it's matched. */
	public static String tidy(String text) {
		return join(terms(text), false);
	}

	/** The name without its "!" terms ("" if it has no others): what it would match if nothing were left out. */
	public static String withoutExclusions(String text) {
		return join(terms(text), true);
	}

	private static String join(List<Term> terms, boolean dropExclusions) {
		StringBuilder sb = new StringBuilder();
		for (Term t : terms) {
			if (dropExclusions && t.kind() == Kind.EXCLUDE) continue;
			if (sb.length() > 0) sb.append(", ");
			sb.append(t.text());
		}
		return sb.toString();
	}

	/**
	 * The term the cursor is in: the one being typed or clicked into. {@code head}: everything before it (with its "!"
	 * if it's an exclusion); {@code body}: the term, without "#"; {@code tag}: whether it started with "#";
	 * {@code tail}: everything after it.
	 */
	public record Typing(String head, String body, boolean tag, String tail) {
		/** The text with this term replaced by {@code value} ("#logs", "stone", "deepslate_*"). */
		public String replace(String value) { return head + value + tail; }

		/** Where the cursor belongs after {@link #replace}: at the end of the new term. */
		public int caret(String value) { return head.length() + value.length(); }
	}

	public static Typing typing(String text, int cursor) {
		String lower = text.toLowerCase(Locale.ROOT);
		cursor = Math.max(0, Math.min(cursor, lower.length()));
		int start = lower.lastIndexOf(',', cursor - 1) + 1, end = lower.indexOf(',', cursor);
		if (end < 0) end = lower.length();
		int a = start, b = end;
		while (a < b && lower.charAt(a) == ' ') a++;
		if (a < b && lower.charAt(a) == '!') { a++; while (a < b && lower.charAt(a) == ' ') a++; }
		while (b > a && lower.charAt(b - 1) == ' ') b--;
		boolean tag = a < b && lower.charAt(a) == '#';
		return new Typing(lower.substring(0, a), lower.substring(tag ? a + 1 : a, b), tag, lower.substring(b));
	}

	/** The last term, as when typing at the end of the text. */
	public static Typing typing(String text) { return typing(text, text.length()); }

	/** The text with its last term replaced by {@code value}. */
	public static String replaceLast(String text, String value) {
		return typing(text).replace(value);
	}

	/**
	 * The name changed to leave {@code id} out: a plain term naming exactly it is dropped (when other terms remain to
	 * match something), otherwise "!id" is added.
	 */
	public static String leaveOut(String text, String id) {
		List<Term> terms = terms(text);
		List<Term> kept = new ArrayList<>();
		boolean dropped = false, others = false;
		for (Term t : terms) {
			if (t.kind() == Kind.PLAIN && t.text().equals(id)) { dropped = true; continue; }
			if (t.kind() != Kind.EXCLUDE) others = true;
			kept.add(t);
		}
		if (dropped && others) return join(kept, false);
		String tidy = join(terms, false);
		return tidy.isEmpty() ? "!" + id : tidy + ", !" + id;
	}
}
