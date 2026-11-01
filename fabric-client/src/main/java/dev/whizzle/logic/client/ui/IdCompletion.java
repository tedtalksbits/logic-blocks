package dev.whizzle.logic.client.ui;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Completing a block or item id as it's typed ("dia" -> "diamond"), Tab to accept (pure Java, unit-tested). Vanilla
 * ids are shown without "minecraft:"; modded ones keep their namespace.
 */
public final class IdCompletion {
	private final List<String> ids;

	/** {@code ids}: every id that may be typed, e.g. from the game's item registry. */
	public IdCompletion(List<String> ids) {
		// Shortest first, so "dia" offers "diamond" before "diamond_axe"; then alphabetical.
		this.ids = ids.stream().map(IdCompletion::shortId).distinct()
			.sorted(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).toList();
	}

	public static String shortId(String id) {
		String s = id.trim().toLowerCase(Locale.ROOT);
		return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
	}

	/**
	 * The id {@code typed} completes to, or null when nothing longer matches, nothing is typed, or it's already a whole
	 * id (so "diamond" doesn't offer "diamond_axe", and Tab then moves on as usual).
	 */
	public String complete(String typed) {
		String t = shortId(typed);
		if (t.isEmpty() || t.equals("*") || ids.contains(t)) return null;
		for (String id : ids) if (id.startsWith(t) && !id.equals(t)) return id;
		return null;
	}

	/** The grey text to show after what's typed, or null. */
	public String suggestion(String typed) {
		String full = complete(typed);
		return full == null ? null : full.substring(shortId(typed).length());
	}

	public boolean known(String id) { return ids.contains(shortId(id)); }
}
