# Test plan: Logic Blocks

Tests 1-14 have passed in a real client against a real Paper server on 26.2 and on 26.3 (client 0.2.0, Paper
26.3-211), and CI runs the automated real-server self-test on every push. Tests 15-25 (side labels and their settings,
client 0.3.0 with plugin 0.2.0) passed in a real 26.3 game. Tests 26-39 (rules v2, plugin 0.3.0 with client 0.4.0) are
new: the plugin side passed on a real server (self-test), the editor hasn't been seen in a real game yet. Rerun the plan after changes to the editor or the
rules, and to judge how it feels.

## Setup (once, about 5 minutes)

1. Close Minecraft (Windows won't replace a mod jar the game has open).
2. Double-click `run\build-and-install.cmd`. It stops the test server (`..\blueprints-mc\run\local-paper`), builds
   and tests both parts, installs the plugin on the server and the client mod in `%APPDATA%\.minecraft\mods\`
   (replacing older Logic Blocks client jars, leaving Fabric API and the Blueprints mod alone), then offers to start
   the server. Without RCON it asks you to type `stop` in the server window and carries on by itself; run
   `run\build-and-install.cmd --enable-rcon` once and it stops the server for you from then on.
3. Launch **fabric-loader-26.3** and join. In creative or survival, `/logic give` gives you a few.

## Tests

| # | Do this | Expect |
|---|---|---|
| 1 | Open a crafting table's recipe book and search "logic" | The Logic Block recipe: comparator on top, redstone left, right and below, daylight detector in the middle. |
| 2 | Place a Logic Block. Right-click it | The editor opens: "No rules yet". Top line: Output 0, your name, its coordinates. |
| 3 | Put red wool on its **north** side and a redstone lamp on another side. In the editor: **+ Rule** (it starts as "Block North is red_wool"), **Save**, **Close** | Under the condition: "now: red_wool (true)", and the rule turns green. After saving, the lamp lights. |
| 4 | Reopen it, keep it open, and break the wool | Within a moment the condition shows "now: air (false)". The lamp goes off. Put it back: true, lamp on. |
| 5 | Change the id box to `blue_wool` and leave without saving | "Discard your changes?" appears. **Keep editing** returns with your edit; **Discard** closes without changing the block. |
| 6 | **Toggle latch:** a button on a block, a repeater from it pointing into the Logic Block's **west** side, a door or lamp on the east. Rule: **Signal** West `>` 0, **Then: Toggle** 15. **Otherwise: Keep**. Save | Each button press flips the door. Holding or releasing doesn't flip it again. |
| 7 | **Pulse:** rule **Players** within 3 `>=` 1, **Then: Pulse** 15 for 40 ticks, otherwise Output 0 | Walk up: the output turns on for 2 seconds, then off while you're still there. Walk away and back: again. |
| 8 | **Items:** a chest below. Rule **Items** Down `coal` `>=` 5 -> 12 | 4 coal: false (the live reading shows the count). 5 coal: true, output 12 (dust next to it shows strength 12 with F3 or a comparator line). Try `*` for "any item". |
| 9 | **Order:** two rules that are both true | The first one wins (green). **↑ / ↓** reorder them; after saving, the output follows the new first rule. |
| 10 | **Dust warning:** run plain redstone dust straight into the side a Signal rule reads | The editor shows a gold warning about dust carrying the block's own output back. With a repeater in front instead, the warning goes away. |
| 11 | Break the Logic Block (survival) | It drops a Logic Block whose tooltip lists its rules. Place it elsewhere: same rules. |
| 12 | `/time set night`, then `/time set day` | The output doesn't change (it ignores daylight). |
| 13 | Stop and start the server, return | Rules, and a latched toggle from test 6, are still there. |
| 14 | `/logic info` while looking at it | The rules in chat (this is what players without the mod see when they right-click). |
| 15 | **Side labels:** look at a Logic Block | **N** (red), **E**, **S**, **W** float just above it within a moment. Press F3: the red N is on the side you face when F3 says "Facing: north". |
| 16 | Walk around it, look at it from above and from close up | The letters always face you and stay on their sides. Not too big, not too small? |
| 17 | Put a block on top of it, and wool on its north side | The letters still show (dimmer where something covers them). |
| 18 | Look at an ordinary daylight detector | No letters. |
| 19 | Press **N** | Above the hotbar: "side labels off"; the letters go. Press N again: back on. Leave and rejoin: your choice is kept. |
| 20 | Options > Controls > Key Binds > **Logic Blocks** | "Side labels (N, E, S, W)", bound to N, can be rebound. |
| 21 | **Label settings:** type `/logicblocks` | "Logic Blocks: side labels" opens: controls on the left, a preview on the right (the block from above with N at the top, and from the south). |
| 22 | Drag **Size**, **Height**, **From centre**, **Background**; click **North** and **E, S, W** to cycle colours | The preview follows every change. Height moves the letters against the "1 block" line. |
| 23 | **Done**, look at a Logic Block | The labels in the world match the preview. Rejoin: still your settings. |
| 24 | Open a Logic Block's editor, click **Labels**, then **Done** | Back in the editor, with unsaved edits still there. |
| 25 | **Reset** | Back to the defaults (100%, 0.62, 0.36, 25%, red and white). |
| 26 | **Sorter:** in an open spot, `/logic demo sorter` | A line of hoppers with a Logic Block and a chest under each appears south-east of you. Within about 30 seconds the stocked mix is sorted: diamonds, emeralds and diamond swords in their chests, the rest in the overflow chest at the east end. |
| 26b | Stand next to a wall and run it again | "Not enough room: ..." names the block in the way; nothing is built. |
| 27 | Fill the diamond chest completely, feed more diamonds | They go to the overflow; the line keeps moving. Empty the chest: diamonds are sorted again. |
| 28 | Right-click one of the sorter's Logic Blocks | The editor opens on the **When** tab: "1. always: move diamond". The move row reads "now: up: hopper, ... -> down: chest" in green. |
| 29 | `/logic demo redstone-sorter` somewhere else | The two-Logic-Block version (glass on top, Logic Blocks on its north side) sorts the same mix. |

## Tests: rules v2

| # | Do this | Expect |
|---|---|---|
| 30 | **Tabs:** open any Logic Block | Two tabs, **If/Else (n)** and **When (n)**, the open one in white. If/Else shows "Otherwise" at the bottom; When doesn't. |
| 31 | **Turns on:** button on a block, repeater into the west side. If/Else rule: **Change** West **turns on** -> **Toggle** 15, otherwise **Keep** | Each press flips a lamp on the east side. Holding the button doesn't flip it again. |
| 32 | **Move in an output rule:** in rule 31, **+ Move items**: `*` from **Up** to **Down**, chests above and below the Logic Block, a few items in the top one | Each press also moves one item down. The list shows "west on -> toggle 15 + move". |
| 33 | **Furnace fuel:** a Logic Block with a chest of coal on top and a furnace to its east. When rule: Move `coal` from **Up** to **East** | Coal fills the furnace's **fuel** slot (it goes in through the furnace's side, as from a hopper). |
| 34 | Same block, Move `iron_ore` from **Up** to **East** | Nothing moves: a furnace's side only takes fuel. Move the Logic Block on top of the furnace (Move ... to **Down**) and the ore goes into the smelting slot. |
| 35 | **Old blocks:** a Logic Block placed with plugin 0.2.0 (before updating) | After updating it still runs its rules, and the editor shows them unchanged. |
| 36 | An old client (0.3.0) on the new server | Right-clicking says the server needs a different version of the mod, instead of opening the editor. |
| 37 | **Middle-click** a Logic Block (survival) with a plain daylight detector and a Logic Block in your inventory | The Logic Block is picked, not the plain detector. With only plain detectors: nothing is picked. |
| 38 | Creative: middle-click a sorter station, then **Ctrl + middle-click** it | A blank Logic Block in your hand; then one whose tooltip lists "move diamond from up to down". Place it: it sorts diamonds. |
| 39 | Break a sorter station's Logic Block (survival) and place it again | It keeps its When rule (this was lost before). |
| 40 | **Typing ids:** in a Move or Items box, type `dia` | `mond` shows in grey after it; **Tab** makes it `diamond`. Type `diamond_` and Tab: `diamond_axe`. In a Block box, `red_w` + Tab gives `red_wool`. |
| 41 | **hand:** hold a diamond, open the editor, look at a Move row | A **hand** button next to the id (and next to **use**, if shown). Click it: the id becomes `diamond` and **hand** goes away. In a Block condition it fills the block's id (holding cobblestone: `cobblestone`); holding a non-block item, no **hand** there. |
| 42 | **Wide screen:** on your ultrawide, open the editor, then `/logicblocks` | The editor sits in a centred column at most 640 GUI pixels wide, on a darker panel, with Save and Close at its right edge. The label settings are centred the same way. On a normal screen nothing changes. |

## Tests: counters and timers (plugin 0.4.0, client 0.5.0)

| # | Do this | Expect |
|---|---|---|
| 43 | `/logic demo lock`, then press the buttons in the order on the signs (1, 2, 3) | The iron door opens for 3 seconds, then shuts. |
| 44 | Right-click the lock's Logic Block, press button 1, then button 2 | The editor's When rules go green as you press; a **Set A** row shows `now: A = 1`, then `A = 2`. Then press 3: the door opens. |
| 45 | Press 1, 3, 2 | The door stays shut (A goes back to 0). Then 1, 2, 3 opens it. |
| 46 | **Counting:** new block, When: **Signal** West `>` 0 -> **Add** 1 to A; If/Else: **Counter** A `>=` 3 -> Output 15. Lever (or button) through a repeater into the west side | Each press adds exactly 1 (holding a lever doesn't keep counting); the output comes on at the third. |
| 47 | **Timer door:** If/Else 1: **Change** West turns on -> Output 15, **+ Action** -> **Start** T1 for `5` seconds. If/Else 2: **Timer** T1 is done -> Output 0. Otherwise **Keep**. A button into the west side, a door on the east | A press opens the door; it shuts 5 seconds later. While open, the Start row shows `now: T1 running, ...s left`. |
| 48 | **Clock:** When: **Timer** T1 is not running -> **Start** T1 for `1` seconds. If/Else: **Timer** T1 is done -> **Pulse** 15 for 4. Otherwise **Keep**. A lamp next to it | The lamp blinks once a second. |
| 49 | Leave test 46's block at A = 2, restart the server | Still A = 2 (one more press turns it on). Save the rules again: A is back to 0. Break and place it: A is 0. |
| 50 | Old client (0.4.0) on the new server | Right-clicking says the mod needs updating. |

## Tests: machines (docs/BUILDS.md)

Each command builds next to you (2 east, 2 south), stocked. Right-click a Logic Block in it to watch.

| # | Do this | Expect |
|---|---|---|
| 51 | `/logic demo iron-crafter` | Within about 35 seconds the chest in front of the crafter has 9 iron blocks and the top chest is empty. Put more ingots in the top chest: more blocks. |
| 52 | `/logic demo rocket-crafter`, then `/logic demo rocket-crafter 1` somewhere else | 18 rockets of flight duration 3 (tooltip), then 18 of flight 1 from the second. |
| 53 | `/logic demo super-smelter` | Raw iron spreads over the furnaces (most of the 8 light up), 16 iron ingots end up in the chest at the bottom east end. Put 64 raw iron in the top chest: all 8 furnaces work. |
| 54 | `/logic demo shulker-loader` | A box is placed, fills with the 27 tools in about 11 seconds, the piston breaks it and it drops into the output chest (bottom west) as a full box. A new empty box is placed. Put more items in the input chest (east end): the next box fills. |
| 55 | `/logic demo shulker-unloader` | The 3 boxes are placed one at a time and emptied; the output chest ends up with all their items and the 3 empty boxes. |
| 56 | In any of them, open a Logic Block | **Fill** rows show `now: fill N` (a crafter 0-9, others 0-15), matching a comparator placed against the same container. |

## Please report

Screenshots of anything wrong, and anything that felt clunky: the button cycling, the layout, wording, the
recipe cost.

## ⚠️ Known limits and decisions for you

1. **It looks like a daylight detector.** A target block was the first plan, but it passes incoming power straight
   through (`docs/DESIGN.md`). A custom look would need a resource pack.
2. **Plain dust as an input latches**, because the block's own output flows back through it. Use a repeater; the
   editor warns. Fixing this in the plugin would mean guessing which part of the dust's power is external.
3. **Only the owner (and operators) can edit.** Set `editing: anyone` in `config.yml` to open it up.
4. **Sides are compass directions.** The mod now labels them (tests 15-20). Players without the mod still use F3.
5. **Moving items is hopper speed:** one item per 8 ticks per Logic Block, only between touching containers.
6. **Machines run at hopper speed** (one item per 8 ticks per Logic Block): a shulker loader takes about 11 minutes for
   a box of full stacks, like a one-hopper vanilla loader. docs/BUILDS.md has the numbers.
7. **Counters and timers are per block and start over when you save or break it.** Their actions run once each time
   the rule becomes true; changes show at the next check (2 ticks). See README, "Counters and timers".
   Timers, counters, copy and paste, and presets come next; variables and wireless links later.
8. **Side labels are on by default, toggled with N.** Only players with the mod see them, and only on the block
   under the crosshair. Their look is each player's own setting (`/logicblocks`); there is no server-wide switch.
