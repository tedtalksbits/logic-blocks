# Test plan: Logic Blocks

Tests 1-14 have passed in a real client against a real Paper server on 26.2 and on 26.3 (client 0.2.0, Paper
26.3-211), and CI runs the automated real-server self-test on every push. Tests 15-25 (side labels and their settings,
client 0.3.0 with plugin 0.2.0) passed in a real 26.3 game. Rerun the plan after changes to the editor or the
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
| 26 | **Sorter:** build two stations from `docs/SORTER.md` (diamond, then diamond_sword), a feeder chest over one hopper, an overflow chest | Fill the feeder with a mix: diamonds and swords end in their chests, everything else in the overflow. |
| 27 | Fill the diamond chest (and its two hoppers) completely, feed more diamonds | They go to the overflow; the line keeps moving. Empty the chest: diamonds are sorted again. |

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
5. **Nothing yet for timers, counters, variables, or wireless links between blocks.** Those are the natural v2.
6. **Side labels are on by default, toggled with N.** Only players with the mod see them, and only on the block
   under the crosshair. Their look is each player's own setting (`/logicblocks`); there is no server-wide switch.
