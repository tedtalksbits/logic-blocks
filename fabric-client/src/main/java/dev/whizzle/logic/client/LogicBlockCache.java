package dev.whizzle.logic.client;

import dev.whizzle.logic.model.ChunkList;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where the server says Logic Blocks are, by chunk, for the current level, so the mod can draw them. Read from
 * chunk-meshing threads ({@link #at}), written on the client thread: each chunk's set is replaced whole, never changed
 * in place. Also holds the player's own placements until the server confirms them. Pure Java (unit-tested).
 */
public final class LogicBlockCache {
	private final Map<Long, Set<Integer>> chunks = new ConcurrentHashMap<>();
	/** The player's own placements the server hasn't confirmed yet, with the tick they stop counting. */
	private final Map<Long, Long> predicted = new HashMap<>();

	public boolean at(int x, int y, int z) {
		Set<Integer> c = chunks.get(ChunkList.key(x >> 4, z >> 4));
		return c != null && c.contains(ChunkList.pack(x, y, z));
	}

	/**
	 * Replaces what's known about a chunk with the server's list; returns the section y indices (block y >> 4) whose
	 * blocks changed and need meshing again. The server's word replaces any prediction in that chunk.
	 */
	public Set<Integer> update(ChunkList list) {
		long key = ChunkList.key(list.cx(), list.cz());
		Set<Integer> now = Set.copyOf(list.packed());
		Set<Integer> before = now.isEmpty() ? chunks.remove(key) : chunks.put(key, now);
		if (before == null) before = Set.of();
		predicted.keySet().removeIf(p -> ChunkList.key(posX(p) >> 4, posZ(p) >> 4) == key);
		Set<Integer> sections = new TreeSet<>();
		for (int p : now) if (!before.contains(p)) sections.add(ChunkList.y(p) >> 4);
		for (int p : before) if (!now.contains(p)) sections.add(ChunkList.y(p) >> 4);
		return sections;
	}

	/** Marks the player's own placement at once (the game draws the block before any server message), until {@code untilTick}. */
	public void predict(int x, int y, int z, long untilTick) {
		chunks.compute(ChunkList.key(x >> 4, z >> 4), (k, old) -> {
			Set<Integer> s = old == null ? new HashSet<>() : new HashSet<>(old);
			s.add(ChunkList.pack(x, y, z));
			return Set.copyOf(s);
		});
		predicted.put(pos(x, y, z), untilTick);
	}

	/** Drops predictions the server never confirmed (the placement failed); returns their positions, to re-mesh. */
	public List<int[]> expire(long tick) {
		List<int[]> out = new ArrayList<>();
		for (Iterator<Map.Entry<Long, Long>> it = predicted.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long, Long> e = it.next();
			if (e.getValue() > tick) continue;
			it.remove();
			int x = posX(e.getKey()), y = posY(e.getKey()), z = posZ(e.getKey());
			chunks.computeIfPresent(ChunkList.key(x >> 4, z >> 4), (k, old) -> {
				Set<Integer> s = new HashSet<>(old);
				s.remove(ChunkList.pack(x, y, z));
				return s.isEmpty() ? null : Set.copyOf(s);
			});
			out.add(new int[]{x, y, z});
		}
		return out;
	}

	public void forget(int cx, int cz) {
		long key = ChunkList.key(cx, cz);
		chunks.remove(key);
		predicted.keySet().removeIf(p -> ChunkList.key(posX(p) >> 4, posZ(p) >> 4) == key);
	}

	public void clear() { chunks.clear(); predicted.clear(); }

	/** Logic Blocks known. */
	public int size() { int n = 0; for (Set<Integer> c : chunks.values()) n += c.size(); return n; }

	// A block position in one long: x and z 26 bits each, y 12 bits (as BlockPos packs it).
	static long pos(int x, int y, int z) { return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF); }
	static int posX(long p) { return (int) (p >> 38); }
	static int posY(long p) { return (int) (p << 52 >> 52); }
	static int posZ(long p) { return (int) (p << 26 >> 38); }
}
