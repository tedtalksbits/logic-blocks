package dev.whizzle.logic;

import dev.whizzle.logic.model.Engine;
import dev.whizzle.logic.model.Program;
import dev.whizzle.logic.model.Wire;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.UUID;

/** A placed Logic Block in a loaded chunk: its program, owner, and runtime state. */
public final class LogicNode {
    public record Key(UUID world, int x, int y, int z) {
        public static Key of(Block b) { return new Key(b.getWorld().getUID(), b.getX(), b.getY(), b.getZ()); }
    }

    public static final int RECORD_FORMAT = 1;

    final Key key;
    final World world;
    Program program;
    final UUID owner;
    final String ownerName;
    final Engine.State state;
    /** Last applied output and when it changed (rate limiting), last evaluation tick. */
    int applied;
    long lastChange = Long.MIN_VALUE / 2, lastEval = Long.MIN_VALUE / 2;
    Engine.Evaluation last;

    LogicNode(World world, int x, int y, int z, Program program, UUID owner, String ownerName, Engine.State state) {
        this.world = world;
        this.key = new Key(world.getUID(), x, y, z);
        this.program = program;
        this.owner = owner;
        this.ownerName = ownerName;
        this.state = state;
        this.applied = state.output;
    }

    public Block block() { return world.getBlockAt(key.x(), key.y(), key.z()); }
    public Location center() { return new Location(world, key.x() + 0.5, key.y() + 0.5, key.z() + 0.5); }
    public Program program() { return program; }
    public UUID owner() { return owner; }
    public String ownerName() { return ownerName; }
    public int output() { return applied; }
    public Engine.State state() { return state; }

    /** Chunk storage record: program, owner, and runtime state (pulse time is stored as ticks remaining). */
    byte[] encode(long now) {
        long remaining = state.pulseUntil < 0 ? -1 : Math.max(0, state.pulseUntil - now);
        return new Wire.Writer().varInt(RECORD_FORMAT).bytes(program.encode()).utf(owner.toString()).utf(ownerName)
            .varInt(state.matched + 2).varInt(state.output).varInt((int) (remaining + 1)).bool(state.toggled).bytes();
    }

    static LogicNode decode(World world, int x, int y, int z, byte[] data, long now) {
        Wire.Reader in = new Wire.Reader(data);
        int format = in.varInt();
        if (format != RECORD_FORMAT) throw new IllegalArgumentException("Unknown Logic Block record format " + format);
        Program program = Program.decode(in.bytes(Program.MAX_BYTES));
        UUID owner = UUID.fromString(in.utf(64));
        String name = in.utf(64);
        int matched = in.varInt() - 2, output = in.varInt();
        long remaining = in.varInt() - 1L;
        boolean toggled = in.bool();
        in.end();
        Engine.State state = new Engine.State(matched, Math.clamp(output, 0, 15), remaining < 0 ? -1 : now + remaining, toggled);
        return new LogicNode(world, x, y, z, program, owner, name, state);
    }
}
