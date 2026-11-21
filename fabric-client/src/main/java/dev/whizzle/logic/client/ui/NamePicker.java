package dev.whizzle.logic.client.ui;

import dev.whizzle.logic.model.IdPattern;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the editor shows under a block or item name while it's typed: names or tags completing the term, what's really
 * there (the container's items, the block on that side) with whether the name fits each, and a pattern to try. Also
 * what a finished name matches, for hovering it (pure Java, unit-tested; {@link NamePanel} draws it).
 */
public final class NamePicker {
	private NamePicker() {}

	/** The line that teaches the four symbols; shown whenever the list is open. */
	public static final String[][] LEGEND = {{"*", "any letters"}, {"#", "tag"}, {"!", "leave out"}, {",", "and also"}};

	/** How the name as typed so far treats something that's there. */
	public enum State { NONE, MATCH, LEFT_OUT, NO_MATCH }

	/**
	 * A line of the list. {@code value}: what choosing it puts in place of the term being typed, or null if the line only
	 * informs. {@code icon}: the id whose icon to draw, or null. {@code special}: a tag or pattern (drawn like one).
	 */
	public record Row(String label, String value, String icon, String note, State state, boolean special) {}

	public record Section(String title, List<Row> rows) {}

	/** {@code tabRow}: the row Tab accepts when none was chosen with the arrow keys (the completion), or -1. */
	public record View(List<Section> sections, int tabRow) {
		public List<Row> rows() {
			List<Row> all = new ArrayList<>();
			for (Section s : sections) all.addAll(s.rows());
			return all;
		}
	}

	/** A pattern that covers several kinds of what's there: "deepslate_*", 3 kinds. */
	public record Offer(String pattern, int kinds) {}

	/** What a name matches: how many, the first few, and what its "!" terms leave out. */
	public record Summary(int count, List<String> sample, int leftOutCount, List<String> leftOut) {}

	private static final int COMPLETIONS = 4, HERE = 5, SAMPLE = 4;

	/**
	 * @param text  the box's text
	 * @param block whether it names blocks (else items)
	 * @param here  what's really there, id to count (a count below 0: not counted, as for a block), or null
	 * @param hereTitle the heading for those, e.g. "In the chest (up)"
	 */
	public static View view(String text, boolean block, IdCompletion ids, Map<String, Integer> here, String hereTitle) {
		List<Section> sections = new ArrayList<>();
		NameText.Typing typing = NameText.typing(text);
		String body = typing.body();
		int tabRow = -1;
		if (!body.isEmpty() && !typing.tag() && body.indexOf('*') >= 0) {
			// A wildcard being typed: show what it matches so far.
			List<Row> rows = new ArrayList<>();
			int n = 0;
			if (IdPattern.valid(body)) for (String id : ids.ids()) if (IdPattern.matches(body, id, block)) {
				if (n++ < SAMPLE) rows.add(new Row(id, null, id, "", State.NONE, false));
			}
			sections.add(new Section(n == 0 ? "\"" + body + "\" matches nothing" : "\"" + body + "\" matches " + n, rows));
		} else if (!body.isEmpty()) {
			List<Row> rows = new ArrayList<>();
			List<String> names = ids.matching(body, typing.tag(), COMPLETIONS);
			boolean whole = typing.tag() ? ids.knownTag(body) : ids.known(body);
			for (String name : names) {
				boolean first = rows.isEmpty() && !whole && name.startsWith(IdCompletion.shortId(body));
				if (first) tabRow = 0;
				if (typing.tag()) {
					TagInfo info = tag(name, block, ids);
					rows.add(new Row("#" + name, "#" + name, info.first(), (first ? "Tab   " : "") + info.size() + (block ? " blocks" : " items"), State.NONE, true));
				} else {
					rows.add(new Row(name, name, name, first ? "Tab" : "", State.NONE, false));
				}
			}
			if (!rows.isEmpty()) sections.add(new Section(typing.tag() ? "Tags" : block ? "Blocks" : "Items", rows));
			else sections.add(new Section(typing.tag() ? "No tag starts with \"" + body + "\"" : "Nothing called \"" + body + "\"", rows));
		}
		String name = evaluable(text);
		if (here != null && !here.isEmpty()) {
			List<Row> rows = new ArrayList<>();
			for (var e : mostFirst(here)) {
				if (rows.size() >= HERE) break;
				State state = name.isEmpty() ? State.NONE : state(name, e.getKey(), block);
				String count = e.getValue() < 0 ? "" : String.valueOf(e.getValue());
				String verdict = switch (state) { case MATCH -> "matches"; case LEFT_OUT -> "left out"; default -> ""; };
				rows.add(new Row(e.getKey(), e.getKey(), e.getKey(), count.isEmpty() || verdict.isEmpty() ? count + verdict : count + "   " + verdict, state, false));
			}
			sections.add(new Section(hereTitle, rows));
			Offer offer = offer(here, body.isEmpty() || typing.tag() ? "" : body, block);
			// Not when the name as typed already takes everything the offer would.
			boolean news = offer != null;
			if (news && !name.isEmpty()) {
				news = false;
				for (String id : here.keySet()) if (IdPattern.matches(offer.pattern(), id, block) && !IdPattern.matches(name, id, block)) news = true;
			}
			if (news) {
				String icon = null;
				for (var e : mostFirst(here)) if (IdPattern.matches(offer.pattern(), e.getKey(), block)) { icon = e.getKey(); break; }
				sections.add(new Section("Try", List.of(new Row(offer.pattern(), offer.pattern(), icon, offer.kinds() + " kinds here", State.NONE, true))));
			}
		}
		return new View(sections, tabRow);
	}

	/** The name as far as it can be judged while typing: tidied, and without a last term that isn't valid yet. Else "". */
	static String evaluable(String text) {
		List<NameText.Term> terms = NameText.terms(text);
		while (!terms.isEmpty()) {
			StringBuilder sb = new StringBuilder();
			for (NameText.Term t : terms) sb.append(sb.length() > 0 ? ", " : "").append(t.text());
			if (IdPattern.valid(sb.toString())) return sb.toString();
			terms = terms.subList(0, terms.size() - 1);
		}
		return "";
	}

	static State state(String name, String id, boolean block) {
		if (IdPattern.matches(name, id, block)) return State.MATCH;
		String without = NameText.withoutExclusions(name);
		if (without.equals(name)) return State.NO_MATCH;
		return without.isEmpty() || IdPattern.matches(without, id, block) ? State.LEFT_OUT : State.NO_MATCH;
	}

	private static List<Map.Entry<String, Integer>> mostFirst(Map<String, Integer> here) {
		List<Map.Entry<String, Integer>> list = new ArrayList<>(here.entrySet());
		list.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
		return list;
	}

	/**
	 * A pattern covering two or more kinds of what's there, built from a word they share with {@code current} (or, if
	 * that's empty or isn't there, with the most plentiful kind): "deepslate_*" when they all start with it, "*_ore"
	 * when they all end with it, else "*deepslate*". Null when there's none, or {@code current} is already a pattern.
	 */
	public static Offer offer(Map<String, Integer> here, String current, boolean block) {
		if (here == null || here.size() < 2 || IdPattern.isPattern(current)) return null;
		Offer offer = current.isEmpty() ? null : offer(here, current, current, block);
		// Nothing typed, or a name that isn't there (the default, a half-typed one): go by the most plentiful kind.
		if (offer == null && !here.containsKey(current)) offer = offer(here, current, mostFirst(here).get(0).getKey(), block);
		return offer;
	}

	private static Offer offer(Map<String, Integer> here, String current, String base, boolean block) {
		String bestWord = null;
		int bestKinds = 1;
		for (String word : words(base)) {
			if (word.length() < 3) continue;
			int kinds = 0;
			for (String id : here.keySet()) if (words(id).contains(word)) kinds++;
			if (kinds > bestKinds || (kinds == bestKinds && bestWord != null && word.length() > bestWord.length())) { bestWord = word; bestKinds = kinds; }
		}
		if (bestWord == null) return null;
		boolean allStart = true, allEnd = true, anyWhole = false;
		for (String id : here.keySet()) {
			String path = path(id);
			if (!words(id).contains(bestWord)) continue;
			if (path.equals(bestWord)) { anyWhole = true; continue; }
			allStart &= path.startsWith(bestWord + "_");
			allEnd &= path.endsWith("_" + bestWord);
		}
		String pattern = allStart ? bestWord + (anyWhole ? "*" : "_*") : allEnd ? (anyWhole ? "*" : "*_") + bestWord : "*" + bestWord + "*";
		int kinds = 0;
		for (String id : here.keySet()) if (IdPattern.matches(pattern, id, block)) kinds++;
		return kinds < 2 || pattern.equals(current) ? null : new Offer(pattern, kinds);
	}

	private static String path(String id) { return id.substring(id.indexOf(':') + 1); }

	private static List<String> words(String id) { return List.of(path(id).split("_")); }

	/** What a whole name matches among everything the game has; null if it isn't a valid pattern. */
	public static Summary summary(String text, boolean block, IdCompletion ids) {
		String name = NameText.tidy(text);
		if (!IdPattern.isPattern(name) || !IdPattern.valid(name)) return null;
		String key = (block ? "b|" : "i|") + name;
		Summary cached = SUMMARIES.get(key);
		if (cached != null) return cached;
		String without = NameText.withoutExclusions(name);
		boolean exclusions = !without.equals(name);
		List<String> sample = new ArrayList<>(), leftOut = new ArrayList<>();
		int count = 0, out = 0;
		for (String id : ids.ids()) {
			if (IdPattern.matches(name, id, block)) { if (count++ < 8) sample.add(id); }
			else if (exclusions && !without.isEmpty() && IdPattern.matches(without, id, block)) { if (out++ < 3) leftOut.add(id); }
		}
		Summary s = new Summary(count, List.copyOf(sample), out, List.copyOf(leftOut));
		if (SUMMARIES.size() < 256) SUMMARIES.put(key, s);
		return s;
	}

	/** Whether one term stands for something: a known id or tag, a wildcard that fits anything. An exclusion counts by what it names. */
	public static boolean termOk(NameText.Term term, boolean block, IdCompletion ids) {
		String t = term.kind() == NameText.Kind.EXCLUDE ? term.label() : term.text();
		String key = (block ? "b|" : "i|") + t;
		Boolean cached = TERMS.get(key);
		if (cached != null) return cached;
		boolean ok;
		if (t.startsWith("#")) ok = ids.knownTag(t.substring(1));
		else if (t.indexOf('*') < 0) ok = ids.known(t);
		else {
			ok = false;
			if (IdPattern.valid(t)) for (String id : ids.ids()) if (IdPattern.matches(t, id, block)) { ok = true; break; }
		}
		if (TERMS.size() < 1024) TERMS.put(key, ok);
		return ok;
	}

	record TagInfo(int size, String first) {}

	static TagInfo tag(String tag, boolean block, IdCompletion ids) {
		String key = (block ? "b|" : "i|") + tag;
		TagInfo cached = TAGS.get(key);
		if (cached != null) return cached;
		int size = 0;
		String first = null;
		for (String id : ids.ids()) if (IdPattern.matches("#" + tag, id, block)) { if (size++ == 0) first = id; }
		TagInfo info = new TagInfo(size, first);
		TAGS.put(key, info);
		return info;
	}

	private static final Map<String, Summary> SUMMARIES = new HashMap<>();
	private static final Map<String, Boolean> TERMS = new HashMap<>();
	private static final Map<String, TagInfo> TAGS = new HashMap<>();

	/** Forget what was worked out about tags and patterns (the game's tags can change when the server reloads them). */
	public static void clearCache() { SUMMARIES.clear(); TERMS.clear(); TAGS.clear(); }
}
