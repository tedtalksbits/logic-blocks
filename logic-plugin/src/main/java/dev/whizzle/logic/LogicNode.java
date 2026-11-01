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

    /** 1: program, owner, output state. 2: also counters, timers (ticks left) and which "when" rules were true. */
    public static final int RECORD_FORMAT = 2;

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

    /** Chunk storage record: program, owner, and runtime state (pulse and timer times are stored as ticks remaining). */
    byte[] encode(long now) {
        long remaining = state.pulseUntil < 0 ? -1 : Math.max(0, state.pulseUntil - now);
        Wire.Writer out = new Wire.Writer().varInt(RECORD_FORMAT).bytes(program.encode()).utf(owner.toString()).utf(ownerName)
            .varInt(state.matched + 2).varInt(state.output).varInt((int) (remaining + 1)).bool(state.toggled);
        out.varInt(state.counters.length);
        for (int c : state.counters) out.varInt(c);
        out.varInt(state.timerEnd.length);
        for (long end : state.timerEnd) out.varInt(end < 0 ? 0 : (int) Math.max(1, end - now));
        return out.varInt(state.alsoWas).bytes();
    }

    static LogicNode decode(World world, int x, int y, int z, byte[] data, long now) {
        Wire.Reader in = new Wire.Reader(data);
        int format = in.varInt();
        if (format != 1 && format != 2) throw new IllegalArgumentException("Unknown Logic Block record format " + format + " (a newer Logic Blocks?)");
        Program program = Program.decode(in.bytes(Program.MAX_BYTES));
        UUID owner = UUID.fromString(in.utf(64));
        String name = in.utf(64);
        int matched = in.varInt() - 2, output = in.varInt();
        long remaining = in.varInt() - 1L;
        boolean toggled = in.bool();
        Engine.State state = new Engine.State(matched, Math.clamp(output, 0, 15), remaining < 0 ? -1 : now + remaining, toggled);
        if (format >= 2) {
            int n = in.varInt();
            if (n < 0 || n > 64) throw new IllegalArgumentException("Bad counter count");
            for (int i = 0; i < n; i++) { int v = in.varInt(); if (i < state.counters.length) state.counters[i] = Math.clamp(v, 0, dev.whizzle.logic.model.Condition.MAX_NUMBER); }
            n = in.varInt();
            if (n < 0 || n > 64) throw new IllegalArgumentException("Bad timer count");
            for (int i = 0; i < n; i++) { int left = in.varInt(); if (i < state.timerEnd.length) state.timerEnd[i] = left <= 0 ? -1 : now + left; }
            state.alsoWas = in.varInt();
        }
        in.end();
        return new LogicNode(world, x, y, z, program, owner, name, state);
    }
}
