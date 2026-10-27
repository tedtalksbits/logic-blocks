# Design notes

Why Logic Blocks work the way they do. Read this before changing the output block, storage, or the tick loop.

## The output block: a daylight detector

A Paper plugin can't make an arbitrary block emit redstone. It can set the state of blocks that already emit power
from a state property. All three candidates were tested on a real Paper 26.2 server (spike, October 2026):

| Block | Result |
|---|---|
| Barrel + comparator (plugin sets the barrel's contents so the comparator reads exactly N) | Exact 0-15, 2-tick delay. Needs a comparator on the output side, and an inventory players or hoppers could raid. Rejected |
| Target block (`power` property) | Exact 0-15 to every side, 1 tick, holds. **But it's a solid block**: a repeater or lever powering it makes it strongly powered, and that passes through to the other sides at 15, ignoring the rules. Rejected |
| **Daylight detector** (`power` property) | Exact 0-15 to every side, 1 tick. Not solid, so incoming power doesn't pass through. It recalculates from sky light every 20 ticks, which fires `BlockRedstoneEvent`; the plugin answers with its own output, so day and night have no effect. **Chosen** |

`LogicItem.BLOCK` names the block, and `LogicManager.onRedstone` pins its output.

## Reading signals per side

`Block.getBlockPower(BlockFace)` in Paper only reports exact strengths for blocks with a power property (dust,
pressure plates) and returns 0 for a lit repeater pointing at the block, while `getBlockPower()` sees 15. Per-side
inputs are essential, so `Signals` calls Minecraft's own `Level.getSignal(neighbourPos, direction)`, the method
redstone itself uses, by reflection. 26.x is unobfuscated, so the names are stable; if they change, `Signals` logs
once and falls back to Paper's method (`docs/UPGRADING.md`). The self-test's "sides" steps catch a regression.

## Feedback through dust

The Logic Block powers every side, and dust next to it connects to it, so that dust also points back in. Minecraft
treats that as an input. Plain dust as an input therefore latches on. Vanilla circuits have the same issue and solve
it with repeaters, so the docs and the editor recommend repeaters for inputs rather than the plugin guessing which
part of a dust line's power is its own.

## Evaluation

- Every Logic Block in a loaded chunk is evaluated every `check-interval-ticks` (default 2). Polling instead of
  reacting to neighbour updates keeps it simple and predictable, and measured cost is small (400 blocks with 8
  rules x 3 conditions: about 2 ms per tick).
- `Engine` is pure Java: `evaluate` checks every condition and finds the first matching rule; `step` applies the
  action to the block's state (`matched`, `output`, `pulseUntil`, `toggled`). "Starts matching" (for pulse and
  toggle) means the matched rule index changed since the last evaluation.
- Output changes are limited to one per 2 ticks (`LogicManager.MIN_CHANGE_TICKS`), so self-wired blocks become
  clocks instead of oscillating every tick. Desired output is never dropped, only delayed.
- The output is re-applied if something else changed the block's power.

## Rules v2: output and "also"

A program has two lists. **Output** rules work as in v1: the first true rule's first action sets the output
(*if / else if / else*). **Also** rules all run when true, every check (independent statements). Before v2, a rule
that moved items would have had to win "first match" against the rules setting the output; two lists avoid that
clash, and read like code a programmer knows. Rules hold up to 3 actions; only an output rule's first action is an
output, the rest act on the world (`Engine.worldActions` lists them in order). A rule with no conditions always
holds.

**Turns on / off** (`Condition.Kind.CHANGE`) compares the side's signal with the previous check (`Engine.State.signals`,
not stored: after a restart nothing "turns on" at the first check). Checks every 2 ticks catch an observer's
2-tick pulse.

**Format 2** (`Program.FORMAT`) adds the actions lists, the "also" list and the move fields. `Program.decode` still
reads format 1, so v1 blocks and items load unchanged and are saved as format 2 the next time they change. The
protocol went to 2 with it: an older client says it needs updating instead of misreading rules.

## Beyond redstone: the five rules

Anything that does more than switch redstone (so far: **Move items**) must pass all five:

1. **Only what vanilla blocks can already do, made easier.** A move is what a hopper does, through the faces a
   hopper would use (`Mover`: furnaces take input from the top, fuel from the sides and bottom, give results from the
   bottom; only containers a hopper works with).
2. **Touching blocks only.** No range, and never loads a chunk.
3. **No faster than the vanilla equivalent.** One item per 8 ticks per Logic Block (`Mover.COOLDOWN`), measured on a
   real server at exactly 2.5 items per second.
4. **Nothing from nothing.** Items move; none are created, duplicated or destroyed.
5. **Respects claims and protection.** Every move fires `InventoryMoveItemEvent`, as a hopper's does, and is skipped
   if a plugin cancels it.

Several moves in one block share the one-item-per-8-ticks budget; the first move that can happen in a check wins.

## Storage

Each block's program, owner and state live in its **chunk's persistent data** under
`logicblocks:b.<x&15>.<y>.<z&15>`, written whenever they change. They travel with the world save, need no
separate file, and are loaded and unloaded with the chunk, so a Logic Block only runs while its chunk is loaded.
If the block at a stored position isn't a daylight detector anymore (WorldEdit, a map edit), the record is dropped
with a log line. Pistons can't move Logic Blocks and explosions don't destroy them.

The item carries the program too (`logicblocks:program` on the item), so breaking and placing keeps the rules.

## Side labels

The client mod draws N, E, S, W over the Logic Block you're looking at. Players had no way to tell the sides apart
except F3. A resource-pack texture was considered and rejected: a Logic Block *is* a daylight detector, so a texture
either changes every daylight detector or needs a hidden block state or a display entity per block, and the owner
wants no visual clash with vanilla at all. Labels drawn by the client touch nothing on the server or in the world.

The client can't tell a Logic Block from a daylight detector, so it asks the server about the one under its
crosshair (`query`/`is`, docs/PROTOCOL.md) and caches the answer for 2 seconds (`LogicBlockLookup`). The letters
float just above the top, inside the block's own column (`SideLabels`), so a neighbour never hides them; like name
tags, a dim copy shows through anything placed on top. North is red, like a compass needle. Drawn with Fabric's
`LevelRenderEvents.COLLECT_SUBMITS` and Minecraft's `submitText` (`SideLabelRenderer`); this is the part to check
first on a Minecraft update. A key (default N) toggles them, saved in `config/logicblocks.properties`.

How they look is each player's choice (`LabelStyle`: size, height, distance from the centre, north and other colours,
backing), set in `LabelSettingsScreen` (the editor's **Labels** button, or `/logicblocks`) and saved in the same file.
There is no server-side setting: it's all visual preference. A menu stops the player turning to look, so the screen
has its own preview instead of showing the world: the block from above (north up, its real top texture) and from the
south (height, with a "1 block" line where a block placed on top would end). Both views place the letters with the
same `SideLabels` maths the world uses.

## Editor

The client opens when the server sends `open` after a right-click; it never asks on its own. While open, the
server sends a `Snapshot` (everything the block can see: the six neighbours, their signals, container contents,
nearby player distances) every 4 ticks. The client evaluates the *draft* rules against it with the same `Engine`,
which is how the editor shows true/false for edits that aren't saved yet. Saving sends the whole program; the
server validates it with the same `Program.problem()` the client uses, plus ownership and reach.
