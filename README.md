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

**Server:** copy `logic-plugin/build/libs/logic-plugin-0.2.0.jar` into `plugins/` and restart. Settings are in
`plugins/LogicBlocks/config.yml`.

**Each player who wants to edit rules:** Fabric for Minecraft 26.3 (Loader 0.19.5), then put
`logic-blocks-client-0.3.0.jar` and `fabric-api-0.164.0+26.3.jar` in `.minecraft/mods`. (Client 0.1.0 is the
same editor built for 26.2. Side labels need client 0.3.0 and plugin 0.2.0; older pairs still work without them.)

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
   item that's actually there.
6. **Save**. The block's output changes within a tick or two.

**Things to build:** an item sorter that also sorts tools and never jams on a full chest
([docs/SORTER.md](docs/SORTER.md)).

Breaking a Logic Block drops it **with its rules**, so you can move it or make copies by placing it again.

### Rule reference

Each rule has 1 to 3 conditions (all must be true) and one action. Up to 8 rules per block, plus *otherwise*.

| Condition | Reads | Compare |
|---|---|---|
| **Block** | the block on a side (north, east, south, west, up, down) | is / is not `red_wool` |
| **Signal** | the redstone signal coming in on a side, or the strongest of any side | = != < <= > >= 0..15 |
| **Items** | how many of an item (or `*` for any item) the chest, barrel, hopper... on that side holds | = != < <= > >= a number |
| **Players** | players within N blocks (1..16) | = != < <= > >= a number |

| Action | Does |
|---|---|
| **Output** N | outputs N for as long as the rule is the one matching |
| **Pulse** N for T ticks | when the rule starts matching, outputs N for T ticks (20 = 1 second), then 0 |
| **Toggle** N | when the rule starts matching, flips between N and 0, and stays there |
| **Keep** | leaves the output as it is |

*Otherwise* can output a number or **Keep**. Use Keep with Toggle and Pulse so they aren't overwritten when the
input goes away. Example: a button feeding a repeater that points into the west side, `signal west > 0 -> toggle 15`,
otherwise **Keep**, is a door latch in one block.

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

`logicblocks.use` (everyone): place Logic Blocks and edit your own. `logicblocks.admin` (operators): edit anyone's,
`give`, `status`.

## Building

Plugin (from the repo root): `gradle build` with Java 25. It runs the tests and writes
`logic-plugin/build/libs/logic-plugin-0.2.0.jar`.

Client: `cd fabric-client && gradlew build` writes `fabric-client/build/libs/logic-blocks-client-0.3.0.jar`. The
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
