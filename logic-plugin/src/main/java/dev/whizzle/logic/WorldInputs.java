package dev.whizzle.logic;

import dev.whizzle.logic.model.Engine;
import dev.whizzle.logic.model.Side;
import dev.whizzle.logic.model.Snapshot;
import org.bukkit.GameMode;
import org.bukkit.Keyed;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.*;

/** Engine inputs read live from the world around one Logic Block. */
final class WorldInputs implements Engine.Inputs {
    private final Block block;

    WorldInputs(Block block) { this.block = block; }

    static BlockFace face(Side side) {
        return switch (side) {
            case NORTH -> BlockFace.NORTH; case EAST -> BlockFace.EAST; case SOUTH -> BlockFace.SOUTH;
            case WEST -> BlockFace.WEST; case UP -> BlockFace.UP; case DOWN -> BlockFace.DOWN; case ANY -> BlockFace.SELF;
        };
    }

    /** "red_wool" for vanilla, "namespace:id" otherwise. */
    static String id(Keyed keyed) {
        NamespacedKey key = keyed.getKey();
        return key.getNamespace().equals(NamespacedKey.MINECRAFT) ? key.getKey() : key.toString();
    }

    @Override public String block(Side side) {
        return side == Side.ANY ? "" : id(block.getRelative(face(side)).getType());
    }

    @Override public int signal(Side side) {
        if (side != Side.ANY) return Signals.from(block, face(side), side.ordinal());
        int max = 0;
        for (Side s : Side.FACES) max = Math.max(max, Signals.from(block, face(s), s.ordinal()));
        return max;
    }

    @Override public int items(Side side, String id) {
        Map<String, Integer> counts = container(side);
        if (counts == null) return -1;
        if (id.equals("*")) return counts.values().stream().mapToInt(Integer::intValue).sum();
        return counts.getOrDefault(id, 0);
    }

    /** Item id → total count in the container on that side, or null if it isn't a container. */
    Map<String, Integer> container(Side side) {
        if (side == Side.ANY) return null;
        Block other = block.getRelative(face(side));
        BlockState state = other.getState(false);
        if (!(state instanceof Container container)) return null;
        Map<String, Integer> counts = new HashMap<>();
        for (ItemStack stack : container.getInventory().getContents())
            if (stack != null && !stack.getType().isAir()) counts.merge(id(stack.getType()), stack.getAmount(), Integer::sum);
        return counts;
    }

    @Override public int players(int radius) {
        return distances(radius).length;
    }

    /** Distances (tenths of a block) of non-spectator players within {@code radius} of the block's centre. */
    int[] distances(int radius) {
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        List<Integer> out = new ArrayList<>();
        for (Player p : center.getNearbyPlayers(radius + 1)) {
            if (p.getGameMode() == GameMode.SPECTATOR) continue;
            int d = Snapshot.tenths(p.getLocation().distance(center));
            if (d <= radius * 10) out.add(d);
        }
        Collections.sort(out);
        return out.stream().mapToInt(Integer::intValue).limit(Snapshot.MAX_PLAYERS).toArray();
    }

    Snapshot snapshot(int output, int matched) {
        String[] blocks = new String[6];
        int[] signals = new int[6];
        List<Map<String, Integer>> containers = new ArrayList<>(6);
        for (Side s : Side.FACES) {
            blocks[s.ordinal()] = block(s);
            signals[s.ordinal()] = signal(s);
            Map<String, Integer> c = container(s);
            containers.add(c == null ? null : Snapshot.fold(c));
        }
        return new Snapshot(blocks, signals, containers, distances(dev.whizzle.logic.model.Condition.MAX_RADIUS), output, matched);
    }
}
