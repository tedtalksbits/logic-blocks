package dev.whizzle.logic;

import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.logging.Logger;

/**
 * The redstone signal arriving at a block from one side.
 *
 * <p>Paper's {@code Block.getBlockPower(BlockFace)} only reports exact strengths for dust-like blocks and misses
 * repeaters, comparators and powered blocks (it returns 0 for a lit repeater pointing at the block, while
 * {@code getBlockPower()} sees 15). So this calls Minecraft's own {@code Level.getSignal(pos, direction)}, the method
 * redstone itself uses, by name. Minecraft 26.x is unobfuscated, so the names are stable across builds; if a future
 * version renames them, this falls back to Paper's per-face method and logs once (see docs/UPGRADING.md).
 */
final class Signals {
    private static MethodHandle handle, getSignal, newPos;
    private static Object[] directions;
    private static boolean ready, failed;

    private Signals() {}

    static synchronized void init(World world, Logger log) {
        if (ready || failed) return;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            Object level = world.getClass().getMethod("getHandle").invoke(world);
            ClassLoader cl = level.getClass().getClassLoader();
            Class<?> blockPos = Class.forName("net.minecraft.core.BlockPos", false, cl);
            Class<?> direction = Class.forName("net.minecraft.core.Direction", false, cl);
            handle = lookup.unreflect(world.getClass().getMethod("getHandle"));
            getSignal = lookup.unreflect(level.getClass().getMethod("getSignal", blockPos, direction));
            newPos = lookup.findConstructor(blockPos, MethodType.methodType(void.class, int.class, int.class, int.class));
            directions = new Object[6];
            String[] names = {"NORTH", "EAST", "SOUTH", "WEST", "UP", "DOWN"};
            for (int i = 0; i < 6; i++) directions[i] = direction.getField(names[i]).get(null);
            ready = true;
        } catch (Throwable t) {
            failed = true;
            log.warning("Exact per-side redstone reading is unavailable on this server version (" + t
                + "); falling back to Paper's Block.getBlockPower(face), which misses repeaters. See docs/UPGRADING.md.");
        }
    }

    static boolean exact() { return ready; }

    /** Signal 0..15 that the neighbour on {@code face} sends into {@code block}. */
    static int from(Block block, BlockFace face, int faceIndex) {
        if (ready) {
            try {
                Object level = handle.invoke(block.getWorld());
                Object pos = newPos.invoke(block.getX() + face.getModX(), block.getY() + face.getModY(), block.getZ() + face.getModZ());
                return (int) getSignal.invoke(level, pos, directions[faceIndex]);
            } catch (Throwable t) {
                failed = true; ready = false;
            }
        }
        return block.getBlockPower(face);
    }
}
