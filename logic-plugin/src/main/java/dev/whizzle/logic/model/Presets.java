package dev.whizzle.logic.model;

import dev.whizzle.logic.model.Condition.Op;

import java.util.List;

/**
 * Ready-made rules: the editor's Presets list, and the programs the plugin's demos build (one source, so a preset is
 * exactly what the self-test checked on a real server). Pure Java, shared with the client.
 */
public final class Presets {
    private Presets() {}

    /** A preset: what it's called, where to put the Logic Block and what to change, and its rules. */
    public record Preset(String name, String description, Program program) {}

    /** Shown in the editor, in this order. */
    public static List<Preset> all() {
        return List.of(
            new Preset("Sorter station", "Under a line hopper, over a chest: moves one item (diamond here; change it) down out of the line. "
                + "Everything else stays in the line. docs/SORTER.md", moveStation("diamond")),
            new Preset("Latch", "A button or lever into the west side (through a repeater) flips the output on and off.",
                new Program(List.of(new Program.Rule(List.of(Condition.change(Side.WEST, true)), Action.toggle(15))), Action.keep())),
            new Preset("Door that closes itself", "A button into the west side opens a door next to the block; it closes 5 seconds later.", selfClosingDoor(100)),
            new Preset("Delayed off", "On while the west input is on, and for 3 seconds after it goes off.", delayedOff(60)),
            new Preset("Clock", "A short pulse every second, from a timer that restarts itself. Change the seconds in the When rule.", clock(20, 4)),
            new Preset("Count to 3", "Each press on the west side adds 1 to counter A; at 3 the output comes on. Set A to 0 to start over.",
                new Program(List.of(new Program.Rule(List.of(Condition.counter("a", Op.GE, 3)), Action.set(15))), Action.set(0),
                    List.of(new Program.Rule(List.of(Condition.change(Side.WEST, true)), Action.add("a", 1))))),
            new Preset("Combination lock", "Buttons on the west, north and east sides, pressed in that order, open a door on the south side for 3 seconds.", lock()),
            new Preset("Iron block crafter", "Chest above, crafter east of it (facing a chest): iron ingots in, iron blocks out. docs/BUILDS.md", ironCrafter()),
            new Preset("Rocket crafter", "Chest above, crafter east with 4 slots enabled: paper and gunpowder in, flight 3 rockets out. docs/BUILDS.md", rocketCrafter(3)),
            new Preset("Super smelter: input", "Above a furnace, under the input line: feeds it while it holds fewer than 4 items. docs/BUILDS.md", smelterInput()),
            new Preset("Super smelter: service", "Under a furnace, over the collection line, fuel line to the south: takes results out, keeps coal in. docs/BUILDS.md", smelterService()),
            new Preset("Shulker loader: brain", "East of the box spot, input chest east of it. docs/BUILDS.md", shulkerBrain(true)),
            new Preset("Shulker unloader: brain", "East of the box spot. docs/BUILDS.md", shulkerBrain(false)),
            new Preset("Shulker: piston", "South of the brain, touching the piston: breaks the box when the brain says done. docs/BUILDS.md", shulkerFire(Side.NORTH, BOX_DONE)),
            new Preset("Shulker: dispenser", "Above the brain, east of the dispenser: places a box when the brain says missing. docs/BUILDS.md", shulkerDispense()));
    }

    // ---- simple ----

    /** The v2 sorter station: also, always, move the item from up (the line hopper) to down (the chest). */
    public static Program moveStation(String item) {
        return Program.EMPTY.withAlso(List.of(new Program.Rule(List.of(), Action.move(item, Side.UP, Side.DOWN))));
    }

    /** A press on the west side: output 15 and start T1; when T1 is done, output 0. */
    public static Program selfClosingDoor(int ticks) {
        return new Program(List.of(
            new Program.Rule(List.of(Condition.change(Side.WEST, true)), List.of(Action.set(15), Action.start("t1", ticks))),
            new Program.Rule(List.of(Condition.timer("t1", true, false)), Action.set(0))), Action.keep());
    }

    /** Stays on while the west signal is on and for {@code ticks} after it goes off (a timer started when it goes off). */
    public static Program delayedOff(int ticks) {
        return new Program(List.of(
            new Program.Rule(List.of(Condition.signal(Side.WEST, Op.GT, 0)), Action.set(15)),
            new Program.Rule(List.of(Condition.timer("t1", true, false)), Action.set(0))), Action.keep(),
            List.of(new Program.Rule(List.of(Condition.signal(Side.WEST, Op.EQ, 0)), Action.start("t1", ticks))));
    }

    /** A clock: a {@code pulse}-tick pulse every {@code period} ticks, from a timer that restarts itself. */
    public static Program clock(int period, int pulse) {
        return new Program(List.of(new Program.Rule(List.of(Condition.timer("t1", true, false)), Action.pulse(15, pulse))), Action.keep(),
            List.of(new Program.Rule(List.of(Condition.timer("t1", false, true)), Action.start("t1", period))));
    }

    /**
     * The combination lock: buttons on the west, north and east sides, pressed in that order, open the door on the
     * south side for 3 seconds. Counter A remembers how far along you are; a press out of order puts it back to 0.
     */
    public static Program lock() {
        Program.Rule open = new Program.Rule(List.of(Condition.counter("a", Op.EQ, 3)), List.of(Action.pulse(15, 60), Action.setCounter("a", 0)));
        return new Program(List.of(open), Action.keep(), List.of(
            new Program.Rule(List.of(Condition.change(Side.WEST, true)), Action.setCounter("a", 1)),
            new Program.Rule(List.of(Condition.change(Side.NORTH, true), Condition.counter("a", Op.EQ, 1)), Action.setCounter("a", 2)),
            new Program.Rule(List.of(Condition.change(Side.EAST, true), Condition.counter("a", Op.EQ, 2)), Action.setCounter("a", 3)),
            new Program.Rule(List.of(Condition.change(Side.NORTH, true), Condition.counter("a", Op.NE, 1)), Action.setCounter("a", 0)),
            new Program.Rule(List.of(Condition.change(Side.EAST, true), Condition.counter("a", Op.NE, 2)), Action.setCounter("a", 0))));
    }

    // ---- crafters ----

    /**
     * The crafter's trigger: when the grid is full (a comparator would read 9) and timer T1 isn't running, output 15
     * for one check and start T1 for half a second. A rising edge, so the crafter crafts once, and another every half
     * second while the grid stays full, so a craft that didn't happen is tried again.
     */
    public static Program.Rule craftTrigger() {
        return new Program.Rule(List.of(Condition.fill(Side.EAST, Op.EQ, 9), Condition.timer("t1", false, true)),
            List.of(Action.set(15), Action.start("t1", 10)));
    }

    /** Iron ingots from the chest above into the crafter east of it, nine to a grid, then a pulse: iron blocks. */
    public static Program ironCrafter() {
        return new Program(List.of(craftTrigger()), Action.set(0),
            List.of(new Program.Rule(List.of(), Action.move("iron_ingot", Side.UP, Side.EAST))));
    }

    /** Rockets of flight {@code flight} (1-3): the crafter has {@code flight} + 1 slots enabled; brings gunpowder up to {@code flight}, and one paper. */
    public static Program rocketCrafter(int flight) {
        return new Program(List.of(craftTrigger()), Action.set(0), List.of(
            new Program.Rule(List.of(Condition.items(Side.EAST, "gunpowder", Op.LT, flight)), Action.move("gunpowder", Side.UP, Side.EAST)),
            new Program.Rule(List.of(Condition.items(Side.EAST, "paper", Op.EQ, 0)), Action.move("paper", Side.UP, Side.EAST))));
    }

    // ---- super smelter ----

    /** Above each furnace: hand it input while it holds fewer than 4 items in all, leaving the rest for the next furnaces. */
    public static Program smelterInput() {
        return Program.EMPTY.withAlso(List.of(new Program.Rule(List.of(Condition.items(Side.DOWN, "*", Op.LT, 4)), Action.move("*", Side.UP, Side.DOWN))));
    }

    /** Below each furnace: results out into the collection line; 1 or 2 coal in from the fuel line (south). */
    public static Program smelterService() {
        return Program.EMPTY.withAlso(List.of(
            new Program.Rule(List.of(), Action.move("*", Side.UP, Side.DOWN)),
            new Program.Rule(List.of(Condition.items(Side.UP, "coal", Op.LT, 2)), Action.move("coal", Side.SOUTH, Side.UP))));
    }

    // ---- shulker loader and unloader ----

    /** What the shulker machine's brain outputs. */
    public static final int BOX_WORKING = 0, BOX_MISSING = 7, BOX_DONE = 15;

    /**
     * The brain, east of the box spot: "done" (15) when the box is full (loader: a comparator would read 15) or empty
     * (unloader), "missing" (7) when there is no box, "working" (0) otherwise. The loader's brain fills the box from
     * the chest east of it.
     */
    public static Program shulkerBrain(boolean loader) {
        Condition done = loader ? Condition.fill(Side.WEST, Op.EQ, 15) : Condition.items(Side.WEST, "*", Op.EQ, 0);
        Program p = new Program(List.of(
            new Program.Rule(List.of(done), Action.set(BOX_DONE)),
            new Program.Rule(List.of(Condition.fill(Side.WEST, Op.GE, 0)), Action.set(BOX_WORKING))), Action.set(BOX_MISSING));
        return loader ? p.withAlso(List.of(new Program.Rule(List.of(), Action.move("*", Side.EAST, Side.WEST)))) : p;
    }

    /** Fires the block next to it when the brain on {@code brain} says {@code code}: a 6-tick pulse, again every second while it still says so. */
    public static Program shulkerFire(Side brain, int code) {
        return new Program(List.of(new Program.Rule(List.of(Condition.signal(brain, Op.EQ, code), Condition.timer("t1", false, true)),
            List.of(Action.pulse(15, 6), Action.start("t1", 20)))), Action.keep());
    }

    /** The dispenser's block: like {@link #shulkerFire} for "missing", only while the dispenser (west) has a box, so an empty one isn't clicked. */
    public static Program shulkerDispense() {
        return new Program(List.of(new Program.Rule(List.of(Condition.signal(Side.DOWN, Op.EQ, BOX_MISSING), Condition.timer("t1", false, true),
            Condition.fill(Side.WEST, Op.NE, 0)), List.of(Action.pulse(15, 6), Action.start("t1", 20)))), Action.keep());
    }
}
