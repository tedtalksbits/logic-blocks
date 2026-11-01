# Logic Blocks

[![CI](https://github.com/tedtalksbits/logic-blocks/actions/workflows/ci.yml/badge.svg)](https://github.com/tedtalksbits/logic-blocks/actions/workflows/ci.yml)

Redstone you can program with simple rules. A **Logic Block** is a crafted block that checks what's around it
and outputs a redstone signal from 0 to 15:

```
when north is red_wool          -> output 15
when coal east < 10             -> output 7
when signal west >= 1           -> toggle 15
when players within 5 >= 1      -> pulse 15 for 20 ticks
otherwise                       -> output 0
```

The first rule that matches decides the output. You write the rules in an editor (Fabric client mod) that shows,
next to every condition, what the block sees right now and whether it's true, before you save.

- **Paper plugin** (`logic-plugin/`): Paper 26.3 (also runs on 26.2), Java 25. Runs the rules and owns all the data.
- **Fabric client mod** (`fabric-client/`): Minecraft 26.3, Fabric Loader 0.19.5, Fabric API. The rule editor.
  Players without it can still place and use Logic Blocks; right-clicking shows the rules in chat.

## Install

Ready-made jars are on the [Releases](https://github.com/tedtalksbits/logic-blocks/releases) page (the release is
named after the plugin version; the client's version is in the notes), or build them yourself (below).

**Server:** copy `logic-plugin/build/libs/logic-plugin-0.4.0.jar` into `plugins/` and restart. Settings are in
`plugins/LogicBlocks/config.yml`.

**Each player who wants to edit rules:** Fabric for Minecraft 26.3 (Loader 0.19.5), then put
`logic-blocks-client-0.5.0.jar` and `fabric-api-0.164.0+26.3.jar` in `.minecraft/mods`. Plugin 0.4.0 needs client
0.5.0 (counters and timers); an older client says it needs updating instead of opening the editor. Existing Logic Blocks and
Logic Block items keep their rules: older programs are read and upgraded automatically.

## Using it

1. **Craft** a Logic Block: a comparator on top, redstone on both sides and below, a daylight detector in the
   middle (changeable in `config.yml`). Operators can use `/logic give`.
2. **Place** it. It looks like a daylight detector but ignores daylight.
3. **Look at it** (with the mod): **N, E, S, W** float over its top, north in red, so you can see which side is
   which. Press **N** to turn the labels off or on (rebind it under Controls > Logic Blocks). Their size, height,
   distance from the centre, colours and backing are yours to change: **Labels** in the editor, or `/logicblocks`.
4. **Right-click** it to open the editor (sneak + right-click with a block places against it instead).
5. **+ Rule**, then click the buttons to cycle through choices and type ids and numbers. Each condition shows
   `now: <what the block sees> (true/false)`; the rule that's true right now is green. **use** fills in the block or
   item that's actually there, **hand** the one you're holding. While typing an id, the rest of it shows in grey:
   **Tab** accepts it.
6. **Save**. The block's output changes within a tick or two.

**Things to build:** an item sorter that also sorts tools and never jams on a full chest: one Logic Block per item
([docs/SORTER.md](docs/SORTER.md); operators can have it built for them with `/logic demo sorter`).

Breaking a Logic Block drops it **with its rules**, so you can move it or make copies by placing it again.
**Middle-click** on a Logic Block picks a Logic Block from your inventory (one with the same rules if you have it),
never a plain daylight detector; in creative it gives you one, and **Ctrl + middle-click** gives a copy with that
block's rules.

### Rule reference

A Logic Block has two lists of rules, on two tabs in the editor:

- **If/Else**: the first rule whose conditions are all true sets the redstone output (*if / else if / else*),
  and *otherwise* covers the rest. Up to 8 rules.
- **When**: *every* rule whose conditions are all true runs, alongside If/Else. Up to 8 rules.
  For moving items, counters and timers.

Each rule has 0 to 3 conditions (all must be true; none means *always*) and up to 3 actions. An output rule's first
action sets the output; the others, and all of a When rule's actions, are moves and counter and timer actions. The difference between the tabs is
*one winner* (If/Else) versus *every true rule* (When): put several independent jobs, like one move per item, in When.

| Condition | Reads | Compare |
|---|---|---|
| **Block** | the block on a side (north, east, south, west, up, down) | is / is not `red_wool` |
| **Signal** | the redstone signal coming in on a side, or the strongest of any side | = != < <= > >= 0..15 |
| **Items** | how many of an item (or `*` for any item) the chest, barrel, hopper... on that side holds | = != < <= > >= a number |
| **Players** | players within N blocks (1..16) | = != < <= > >= a number |
| **Change** | the signal on a side (or any side) has just turned on or off | turns on / turns off: true for one check, so a held button counts once |
| **Counter** | this block's counter A, B, C or D | = != < <= > >= a number |
| **Timer** | this block's timer T1, T2, T3 or T4 | is / is not running, is / is not done (done: true for the one check when it runs out) |

| Action | Does |
|---|---|
| **Output** N | outputs N for as long as the rule is the one matching |
| **Pulse** N for T ticks | when the rule starts matching, outputs N for T ticks (20 = 1 second), then 0 |
| **Toggle** N | when the rule starts matching, flips between N and 0, and stays there |
| **Keep** | leaves the output as it is |
| **Move** item from side to side | moves one item (or `*` for any) from the container on one side to the container on another, every 8 ticks at most (a hopper's speed). Not an output: it goes after an If/Else rule's output, or in a When rule |
| **Add** N to / **Subtract** N from a counter | counts up or down (counters stay between 0 and 100000) |
| **Set** a counter to N | sets it, for example back to 0 |
| **Start** a timer for S seconds | it runs out after S seconds (0.05 to 3600); starting it again while it runs starts it over |
| **Stop** a timer | stops it without it being done |

*Otherwise* can output a number or **Keep**. Use Keep with Toggle and Pulse so they aren't overwritten when the
input goes away. Example: a button feeding a repeater that points into the west side, `signal west turns on -> toggle
15`, otherwise **Keep**, is a door latch in one block.

**Moving items** works like a hopper, never more: only between containers touching the Logic Block (chests, barrels,
hoppers, droppers, dispensers, shulker boxes, furnaces), one item every 8 ticks, nothing created or lost. Furnaces take
smelting input through their top and fuel through their sides, and give results out of their bottom, as for hoppers.
Protection plugins that stop hoppers stop it too. Examples:

```
When:    always -> move diamond from up to down                     a sorter station (docs/SORTER.md)
When:    coal north >= 1 and coal down < 8 -> move coal from north to down     keep a furnace fuelled
If/Else: signal west turns on -> toggle 15, then move * from up to down    a latch that also empties a chest
```

**Counters and timers** are the block's own memory: 4 counters (A to D) and 4 timers (T1 to T4) per Logic Block,
which no other block can see (blocks talk through redstone, as always).

- Counter and timer actions run **once each time their rule becomes true**, like Pulse and Toggle, so `signal west
  > 0 -> add 1 to A` counts presses, not ticks. Moves are the exception: they repeat while their rule is true.
- Every rule sees the counters and timers as they were at the start of the check; changes show at the next check
  (2 ticks later). When two rules change the same counter in one check, they apply in order: If/Else first, then
  When rules from the top.
- They survive restarts and chunk reloads. **Saving new rules, or breaking the block, starts them over** (the item
  carries the rules, not the memory). The editor shows their current values.

```
If/Else: signal west turns on -> output 15, then start T1 for 5 seconds     a door that closes 5 seconds after a press
If/Else: T1 is done -> output 0                                             (otherwise: Keep)
When:    T1 is not running -> start T1 for 10 seconds                       a clock: with "If/Else: T1 is done -> pulse 15"
When:    signal west = 0 -> start T1 for 3 seconds                          off 3 seconds after the input (with "signal west > 0 -> output 15", "T1 is done -> output 0")
```

`/logic demo lock` builds a **combination lock**: three buttons pressed in the right order open an iron door for 3
seconds, and a press out of order starts over. Its rules, in the editor:

```
When:    signal west turns on -> set A to 1
When:    signal north turns on and counter A = 1 -> set A to 2
When:    signal east turns on and counter A = 2 -> set A to 3
When:    signal north turns on and counter A != 1 -> set A to 0
When:    signal east turns on and counter A != 2 -> set A to 0
If/Else: counter A = 3 -> pulse 15 for 60 ticks, then set A to 0             (otherwise: Keep)
```

### Good to know

- **Sides are compass directions.** The block has no front; with the mod, look at it to see N, E, S, W (or press F3
  to see which way you're facing).
- **Bring signal inputs in through a repeater or comparator**, or put a lever or button on a solid block that touches
  the Logic Block. Dust touching the Logic Block also carries the block's own output back in (it powers every side),
  so plain dust as an input latches on. The editor warns about this.
- **The output reaches every side**, like a redstone block: dust, lamps, pistons, doors and repeaters next to it.
  Power coming in does not pass through it to the other sides.
- **It only runs in loaded chunks**, like all redstone.
- **One output change per 2 ticks at most**, so a Logic Block wired into itself becomes a clock instead of lagging
  the server.
- Rules are checked every 2 ticks (`check-interval-ticks`). 400 busy blocks cost about 2 ms per tick on a test server.

## Configuration

`plugins/LogicBlocks/config.yml`; restart after editing.

| Key | Default | Meaning |
|---|---|---|
| `recipe.shape` / `recipe.ingredients` | comparator, daylight detector, 4 redstone | Shaped crafting recipe |
| `check-interval-ticks` | 2 | How often rules are checked (1-20) |
| `max-per-chunk` | 64 | Most Logic Blocks in one chunk |
| `editing` | `owner` | `owner`: only whoever placed it (and operators) can change it. `anyone`: everybody |

## Commands and permissions

| Command | Who | Does |
|---|---|---|
| `/logic info` | everyone | Shows the rules of the Logic Block you're looking at |
| `/logic give [player] [amount]` | operators | Gives Logic Blocks |
| `/logic status` | operators | How many are loaded and how many editors are open |
| `/logic demo sorter [item ...]` | operators | Builds a working, stocked item sorter next to you, one Logic Block per station ([docs/SORTER.md](docs/SORTER.md)) |
| `/logic demo redstone-sorter [item ...]` | operators | The same with pure redstone, two Logic Blocks per station |
| `/logic demo lock` | operators | Builds a combination lock (a counter) north of you |

`logicblocks.use` (everyone): place Logic Blocks and edit your own. `logicblocks.admin` (operators): edit anyone's,
`give`, `status`.

## Building

Plugin (from the repo root): `gradle build` with Java 25. It runs the tests and writes
`logic-plugin/build/libs/logic-plugin-0.4.0.jar`.

Client: `cd fabric-client && gradlew build` writes `fabric-client/build/libs/logic-blocks-client-0.5.0.jar`. The
client compiles the plugin's rule model (`dev.whizzle.logic.model`) from `logic-plugin/src`, so both sides share
one implementation of the rules, their encoding and their evaluation.

**Development loop:** `run\build-and-install.cmd` (Windows) or `run/build-and-install.sh` stops your local test
server, builds and tests both parts, installs the plugin on the server and the client mod in Minecraft's mods folder,
and offers to start the server again. Point it at your setup with `LOGIC_SERVER_DIR` (the Paper server folder) and
`LOGIC_MODS_DIR` (default: the standard `.minecraft/mods`); `--help` lists the options. With `--enable-rcon` (once,
local test servers only) it stops the server through RCON instead of asking you to type `stop`.

## Project layout

| Path | What |
|---|---|
| `logic-plugin/src/main/java/dev/whizzle/logic/model/` | The rule language: conditions, actions, programs, encoding, the evaluation engine, snapshots. Pure Java, shared with the client |
| `logic-plugin/src/main/java/dev/whizzle/logic/` | Paper side: placing and storing blocks, the tick loop, reading the world, the editor protocol |
| `fabric-client/` | The editor screen and networking |
| `paper-selftest/` | Development-only plugin that builds circuits on a real Paper server and checks them (see `docs/UPGRADING.md`) |
| `docs/` | `DESIGN.md` (why it works this way), `PROTOCOL.md`, `TEST_PLAN.md`, `UPGRADING.md` |
