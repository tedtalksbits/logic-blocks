# Builds: crafters, a super smelter, shulker loaders

Five machines made of vanilla blocks, with Logic Blocks doing the filling, emptying and timing. Each one is built,
programmed and stocked by a command, and the self-test (`paper-selftest`, "machines" steps) runs exactly that build
on a real Paper 26.3 server and checks the results:

| Command | What it makes | Logic Blocks | Checked on a real server |
|---|---|---|---|
| `/logic demo iron-crafter` | iron blocks from iron ingots | 1 | 81 ingots -> 9 blocks, nothing left over |
| `/logic demo rocket-crafter [1-3]` | firework rockets of that flight duration (default 3) | 1 | 6 paper + 18 gunpowder -> 18 flight-3 rockets |
| `/logic demo super-smelter [1-8]` | that many furnaces (default 8) working together | 2 per furnace | 16 raw iron -> 16 ingots, spread over 6 or more of 8 furnaces |
| `/logic demo shulker-loader` | full shulker boxes from loose items | 3 | two boxes of 27 filled and collected, nothing leaks |
| `/logic demo shulker-unloader` | loose items (and empty boxes) from full boxes | 3 | 3 boxes emptied, every item and every box collected |

Each builds starting 2 blocks east and 2 south of you, needs a clear space (it says how much if there isn't), and is
owned by you. Right-click any of its Logic Blocks to see its rules live.

Two things in the rules exist for these machines: the **Fill** condition (how full a container is, 0 to 15, exactly
what a comparator reads; a crafter reads 0 to 9, its filled and disabled slots) and **moves into a crafter**, which
fill its enabled slots the way a hopper does: empty slots first, then evenly.

## Crafters (iron blocks, rockets)

```
 layer +1   [ input chest  ]
 layer  0   [ Logic Block  ] [ crafter, facing south ]
                             [ output chest           ]   (south of the crafter)
```

The Logic Block has the input chest above it and the crafter east of it. A vanilla crafter crafts once each time it
gets a redstone pulse, from whatever is in its grid, and puts the result in the container it faces.

**Iron blocks** (all 9 slots enabled):

| | Conditions | Does |
|---|---|---|
| When | always | Move `iron_ingot` from Up to East |
| If/Else | **Fill** East = 9, **Timer** T1 is not running | Output 15, then Start T1 for 0.5 seconds |
| Otherwise | | Output 0 |

Moves fill the 9 slots one ingot each. When a comparator would read 9 (the grid is full), the block outputs 15 for
one check and starts T1: a rising edge, so the crafter crafts. If the grid were still full half a second later (a
craft that didn't happen), it pulses again.

**Rockets** (flight 3: the first 4 slots enabled, the other 5 disabled; flight 1 or 2 enables 2 or 3):

| | Conditions | Does |
|---|---|---|
| When | **Items** East `gunpowder` < 3 | Move `gunpowder` from Up to East |
| When | **Items** East `paper` = 0 | Move `paper` from Up to East |
| If/Else | **Fill** East = 9, **Timer** T1 is not running | Output 15, then Start T1 for 0.5 seconds |
| Otherwise | | Output 0 |

Disabled slots count as filled, so "Fill = 9" means "every enabled slot has something". The two When rules make sure
it's 3 gunpowder and 1 paper, never 4 gunpowder. The recipe is shapeless, so it doesn't matter which slot gets which.

Any other crafter recipe works the same way: disable the slots the recipe leaves empty (click them in the crafter),
and give the Logic Block one When rule per ingredient, like the rocket's.

## Super smelter

```
 layer +5   [ input chest ]
 layer +4   [ hopper -> ] [ hopper -> ] [ hopper -> ] ...      the input line
 layer +3                 [ LB in     ] [ LB in     ] ...
 layer +2   (fuel chest)  [ furnace   ] [ furnace   ] ...      fuel chest one block south of the line start
 layer +1   (fuel line, one block south: hopper -> hopper -> ...)
            [ hopper -> ] [ LB out    ] [ LB out    ] ...
 layer  0                 [ hopper -> ] [ hopper -> ] ... [ output chest ]   the collection line
```

Each furnace has a Logic Block above it and one below it.

| Logic Block | Rule | Why |
|---|---|---|
| above ("in") | When: **Items** Down `*` < 4 -> Move `*` from Up to Down | Gives the furnace something to smelt while it holds fewer than 4 items in all (1-2 coal, the one cooking, one waiting), and leaves the rest in the line for the next furnaces |
| below ("out") | When: always -> Move `*` from Up to Down | Takes results out of the furnace's bottom into the collection line |
| below ("out") | When: **Items** Up `coal` < 2 -> Move `coal` from South to Up | Tops up fuel from the fuel line south of it, 1 or 2 at a time, so every furnace gets its share |

The "fewer than 4" is what spreads the work: a plain hopper line would fill the first furnace with 64 items before
the second got any. With 8 furnaces it smelts 0.8 items per second (8 times one furnace), well under what one hopper
line carries (2.5 per second).

Using another fuel: change `coal` in the "out" blocks' second rule (charcoal, say) and put that in the fuel chest.
For blast furnaces or smokers, put them in place of the furnaces; the rules stay the same.

## Shulker loader and unloader

Everything is placed around the spot S where a box sits:

| Block | Where |
|---|---|
| dispenser, facing down | above S; a hopper (facing down) on top of it, and the chest for boxes on top of that |
| piston, facing north | south of S |
| catch cell | north of S: air with glass on its other three sides, over the **catch hopper** (facing west into the output chest) |
| under S | the unloader's hopper (facing north into the catch hopper); the loader has a solid block |
| glass | west of S |
| **LB-C**, the brain | east of S |
| **LB-D** | above the brain (so it touches the dispenser) |
| **LB-P** | south of the brain (so it touches the piston) |
| input chest (loader) | east of the brain |

The dispenser places a box on the spot. When it's done, the piston breaks it (shulker boxes break when a piston pushes
them; that's vanilla) and pushes the dropped box into the catch cell, where it falls into the catch hopper and on to
the output chest. The glass keeps the dropped box from bouncing anywhere else: the self-test broke 48 boxes this way
and collected every one. The unloader's hopper under the spot empties the box, as vanilla hoppers do.

The Logic Blocks talk by redstone strength. The **brain** (LB-C), east of the spot, says what's going on there:

| Brain output | Means | Loader | Unloader |
|---|---|---|---|
| 15 | done | **Fill** West = 15 (the box is full) | **Items** West `*` = 0 (the box is empty) |
| 0 | working | **Fill** West >= 0 (there is a box) | same |
| 7 | missing | otherwise (air, or the piston head) | same |

| Logic Block | Rules |
|---|---|
| LB-P, south of the brain | If/Else: **Signal** North = 15 and **Timer** T1 is not running -> Pulse 15 for 6 ticks, then Start T1 for 1 second; otherwise Keep. Fires the piston, and again every second if the box is still there. |
| LB-D, above the brain | the same with **Signal** Down = 7 and **Fill** West != 0 (the dispenser has a box): places the next box, and fires again as soon as the dispenser is refilled, without clicking while it's empty |
| brain (loader) | also When: always -> Move `*` from East to West: fills the box from the input chest |

**Speed:** the loader puts one item in every 8 ticks (one hopper's speed), so a box of 27 unstackable items takes
11 seconds and a box of 27 full stacks about 11 minutes, as with a one-hopper vanilla loader. The unloader empties
at the hopper's speed too. Both demos come stocked with unstackable tools so you see a box go through quickly.

## Limits

- **Hopper speed throughout** (the five rules, `docs/DESIGN.md`): one item per 8 ticks per Logic Block.
- **The demos use undyed shulker boxes,** but any colour works: nothing in the rules names the colour.
- **Item ids only:** the crafter and smelter rules name items, not enchantments or contents.
