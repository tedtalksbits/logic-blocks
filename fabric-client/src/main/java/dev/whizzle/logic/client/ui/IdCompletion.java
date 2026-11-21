package dev.whizzle.logic.client.ui;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Completing a block or item name as it's typed ("dia" -> "diamond"), Tab to accept (pure Java, unit-tested). Vanilla
 * ids are shown without "minecraft:"; modded ones keep their namespace. With several terms ("*stone*, !cobb") it
 * completes the last one; a term starting {@code #} completes a tag ("#sla" -> "#slabs").
 */
public final class IdCompletion {
	private final List<String> ids, tags;

	public IdCompletion(List<String> ids) { this(ids, List.of()); }

	/** {@code ids}: every id that may be typed, e.g. from the game's item registry; {@code tags}: its tag names. */
	public IdCompletion(List<String> ids, List<String> tags) {
		this.ids = sorted(ids);
		this.tags = sorted(tags);
	}

	// Shortest first, so "dia" offers "diamond" before "diamond_axe"; then alphabetical.
	private static List<String> sorted(List<String> names) {
		return names.stream().map(IdCompletion::shortId).distinct()
			.sorted(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder())).toList();
	}

	public static String shortId(String id) {
		String s = id.trim().toLowerCase(Locale.ROOT);
		return s.startsWith("minecraft:") ? s.substring("minecraft:".length()) : s;
	}

	/**
	 * What {@code typed} completes to (the whole text, its last term completed), or null when nothing longer fits,
	 * the last term is empty or a wildcard, or it's already a whole id or tag (so Tab then moves on as usual).
	 */
	public String complete(String typed) {
		String lower = typed.toLowerCase(Locale.ROOT);
		int cut = Math.max(lower.lastIndexOf(','), lower.lastIndexOf('!')) + 1;
		String rest = lower.substring(cut), term = rest.stripLeading();
		String head = lower.substring(0, cut) + rest.substring(0, rest.length() - term.length());
		boolean tag = term.startsWith("#");
		String t = shortId(tag ? term.substring(1) : term);
		if (t.isEmpty() || t.indexOf('*') >= 0) return null;
		List<String> pool = tag ? tags : ids;
		if (pool.contains(t)) return null;
		for (String name : pool) if (name.startsWith(t)) return head + (tag ? "#" : "") + name;
		return null;
	}

	/** The grey text to show after what's typed, or null. */
	public String suggestion(String typed) {
		String full = complete(typed);
		String lower = typed.toLowerCase(Locale.ROOT);
		return full == null || !full.startsWith(lower) ? null : full.substring(lower.length());
	}

	/** Every id, vanilla ones without "minecraft:", shortest first. */
	public List<String> ids() { return ids; }

	public List<String> tags() { return tags; }

	/**
	 * Up to {@code limit} names (or tags) for a term being typed: those starting with it, shortest first (so the first
	 * is what {@link #complete} picks), then those with it anywhere. Empty for an empty term or one with a wildcard.
	 */
	public List<String> matching(String term, boolean tag, int limit) {
		String t = shortId(term);
		List<String> out = new java.util.ArrayList<>();
		if (t.isEmpty() || t.indexOf('*') >= 0) return out;
		List<String> pool = tag ? tags : ids;
		for (String name : pool) { if (out.size() >= limit) return out; if (name.startsWith(t)) out.add(name); }
		for (String name : pool) { if (out.size() >= limit) return out; if (!name.startsWith(t) && name.contains(t)) out.add(name); }
		return out;
	}

	public boolean known(String id) { return ids.contains(shortId(id)); }

	public boolean knownTag(String tag) { return tags.contains(shortId(tag)); }
}
