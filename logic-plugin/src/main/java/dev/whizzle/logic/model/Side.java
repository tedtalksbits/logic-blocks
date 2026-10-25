package dev.whizzle.logic.model;

/** A face of the Logic Block, in compass terms (the block has no front). ANY is only valid for signals. */
public enum Side {
    NORTH(0, 0, -1), EAST(1, 0, 0), SOUTH(0, 0, 1), WEST(-1, 0, 0), UP(0, 1, 0), DOWN(0, -1, 0), ANY(0, 0, 0);

    public final int dx, dy, dz;

    Side(int dx, int dy, int dz) { this.dx = dx; this.dy = dy; this.dz = dz; }

    public static final Side[] FACES = {NORTH, EAST, SOUTH, WEST, UP, DOWN};

    public String label() { return name().charAt(0) + name().substring(1).toLowerCase(java.util.Locale.ROOT); }
}
