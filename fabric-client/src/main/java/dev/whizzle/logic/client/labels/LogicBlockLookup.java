package dev.whizzle.logic.client.labels;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongConsumer;

/**
 * Which daylight detectors are Logic Blocks, as far as this client knows. A Logic Block looks exactly like a daylight
 * detector to the client, so the mod asks the server about the one under the crosshair (docs/PROTOCOL.md, "query")
 * and remembers the answer for a couple of seconds. Positions are {@code BlockPos.asLong()} values. Pure Java.
 */
public final class LogicBlockLookup {
	/** Ticks an answer stays fresh. Still looking after that asks again, so a swapped block is noticed. */
	public static final int FRESH_TICKS = 40;
	/** Ticks to wait for an answer before asking again (a dropped packet, or an older server that never answers). */
	public static final int RETRY_TICKS = 20;
	/** Upper bound on remembered positions; the cache starts over when it fills. */
	static final int MAX_ENTRIES = 256;

	private record Known(boolean logic, long at) {}

	private final Map<Long, Known> known = new HashMap<>();
	private final Map<Long, Long> asked = new HashMap<>();

	/**
	 * Whether the block at {@code pos} should be labelled now. Calls {@code ask} when the server should be asked about
	 * it. While a stale answer is being refreshed it keeps counting, so the labels don't flicker.
	 */
	public boolean isLogic(long pos, long now, LongConsumer ask) {
		Known k = known.get(pos);
		if (k == null || now - k.at >= FRESH_TICKS) {
			Long last = asked.get(pos);
			if (last == null || now - last >= RETRY_TICKS) {
				if (asked.size() >= MAX_ENTRIES) asked.clear();
				asked.put(pos, now);
				ask.accept(pos);
			}
		}
		return k != null && k.logic;
	}

	/** The server's answer. */
	public void answer(long pos, boolean logic, long now) {
		asked.remove(pos);
		if (known.size() >= MAX_ENTRIES && !known.containsKey(pos)) known.clear();
		known.put(pos, new Known(logic, now));
	}

	/** The block there changed into something that can't be a Logic Block (broken, replaced). */
	public void forget(long pos) {
		known.remove(pos);
		asked.remove(pos);
	}

	/** Left the server or changed worlds. */
	public void clear() {
		known.clear();
		asked.clear();
	}

	int size() { return known.size(); }
}
