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
	 * The term being typed, at the end of the text. {@code head}: everything before it (with a "!" if it's an
	 * exclusion); {@code body}: what's typed of it so far, without "#"; {@code tag}: whether it started with "#".
	 */
	public record Typing(String head, String body, boolean tag) {}

	public static Typing typing(String text) {
		String lower = text.toLowerCase(Locale.ROOT);
		int cut = Math.max(lower.lastIndexOf(','), lower.lastIndexOf('!')) + 1;
		String rest = lower.substring(cut), term = rest.stripLeading();
		String head = lower.substring(0, cut) + rest.substring(0, rest.length() - term.length());
		boolean tag = term.startsWith("#");
		return new Typing(head, (tag ? term.substring(1) : term).stripTrailing(), tag);
	}

	/** The text with the term being typed replaced by {@code value} ("#logs", "stone", "deepslate_*"). */
	public static String replaceLast(String text, String value) {
		return typing(text).head() + value;
	}
}
