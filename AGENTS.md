# Logic Blocks: notes for agents

Start here, then read `README.md` (what it does, how to use it) and `docs/DESIGN.md` (why it works this way).
`docs/PROTOCOL.md`, `docs/TEST_PLAN.md`, `docs/UPGRADING.md` and `docs/CI.md` cover the rest.

## What this is

Programmable redstone for a small Paper server. A crafted **Logic Block** (a daylight detector underneath) checks
rules like `when north is red_wool -> output 15` and outputs a redstone signal 0-15 on every side.

- `logic-plugin/`: Paper plugin (Java 25). Runs everything, stores everything.
- `fabric-client/`: Fabric mod with the rule editor. Separate Gradle build.
- `logic-plugin/src/main/java/dev/whizzle/logic/model/`: the rule language (conditions, actions, programs, encoding,
  `Engine`, `Snapshot`). Pure Java, **compiled into both** the plugin and the client (see `fabric-client/build.gradle`).
  Change it once; both sides follow.
- `paper-selftest/`: development-only plugin that builds circuits on a real Paper server and checks the redstone.
- `ci/` and `.github/workflows/ci.yml`: GitHub Actions (plugin, client, real-Paper self-test). See `docs/CI.md`.

## Status (2026-10-25)

- v1 complete. Minecraft/Paper **26.3** (plugin also runs on 26.2: `api-version` is a minimum).
- Plugin: 17 unit tests pass; the real-server self-test passes on Paper 26.2-130 and 26.3-211.
- Client: 6 unit tests pass. Exercised end to end in a real **26.2** game. The **26.3** build (0.2.0) compiles with no
  code changes but has **not been launched in 26.3 yet**; the owner is running `docs/TEST_PLAN.md` tests 2-6.

## Open items

1. 26.3 client confirmation from the owner's test (above).
2. CI: done (`docs/CI.md`): plugin build and tests, client build and tests, real-Paper 26.3 self-test. The self-test
   script was verified on Paper 26.3-211; the first run on GitHub confirms the rest. Release jars on tags later. No
   automatic deploy to the live server.
3. v2 ideas, not started: timers, counters, variables, wireless links between Logic Blocks, showing the sides in the
   world while the editor is open.

## Decisions already made (don't reopen without asking)

| Decision | Why |
|---|---|
| Its own plugin and mod, unrelated to any other project | Owner's call |
| Adds a programmable block that outputs real redstone, rather than replacing redstone | Works with every existing circuit; owner is open to "an alternative to redstone" later |
| Editor is a mod screen only (no book-and-quill fallback); players without the mod see rules in chat | Owner's call |
| Crafting recipe, configurable (comparator, daylight detector, 4 redstone) | Owner: "crafting recipe 100%" |
| Output block is a **daylight detector** | Target block conducts incoming power through itself; barrel+comparator needs a comparator and an exploitable inventory. Tested on real Paper, see `docs/DESIGN.md` |
| Sides are compass directions | The block has no front |
| Signal inputs should come through a repeater; the editor warns about dust | The block powers all sides, so touching dust carries its own output back (latches) |
| Evaluate every 2 ticks (polling), at most one output change per 2 ticks | Simple, measured cheap (about 1-2 ms per tick for 400 heavy blocks), self-wired blocks become clocks not lag |
| Programs and state stored in the chunk's persistent data; the item carries the program when broken | Moves with the world save, runs only in loaded chunks like redstone |
| Owner (and operators) edit by default; `editing: anyone` opens it | Owner's call |
| v1 inputs: block on a side, signal on a side, items in an adjacent container, players nearby | Owner: "fine for v1" |

## Working rules

- Commit locally; **the owner pushes** to GitHub.
- Put anything that needs the owner's decision at the top of your report, clearly marked. Keep reports short; no
  "•" separators.
- Test on a real Paper server before claiming redstone behaviour works. MockBukkit doesn't simulate redstone.

## Building and testing

- Plugin: `gradle build` at the repo root (Java 25, Gradle 9.1+). Jar: `logic-plugin/build/libs/logic-plugin-<v>.jar`.
- Client: `cd fabric-client && gradlew build` (Loom 1.19.2 needs Gradle 9.8.0). Jar:
  `fabric-client/build/libs/logic-blocks-client-<v>.jar`.
- Self-test: `ci/run-selftest.sh <paper.jar> <new-folder>` (Linux, Git Bash, CI) sets up a throwaway flat-world
  server, runs it with `-Dlogic.selftest.exit=true`, and fails unless `plugins/LogicSelfTest/report.txt` ends
  `SELFTEST PASSED`. Offline, add `PAPER_SEED_DIR=<existing server folder>`.
- CI runs all three on every push to `main` (`docs/CI.md`).
- Windows: `run\build-and-install.cmd` builds both. It currently borrows the JDK and Gradle from a sibling
  `blueprints-mc\.tools` folder and installs onto the owner's local test server there; if that folder is gone,
  point it at any JDK 25 and Gradle instead.

## Cloud workspace pitfalls (if you work in a remote Linux container)

- The container usually **can't reach** Maven Central, the Paper/Fabric Maven repos, Mojang or GitHub release files.
  Build offline from the owner's Gradle cache: stage `caches/modules-2/files-2.1` and `caches/fabric-loom/<mc>` from
  the owner's computer, convert `files-2.1/<group>/<artifact>/<version>/<hash>/<file>` into a Maven layout, and point
  Gradle at it with an init script that replaces all repositories.
- A Paper server can't download its vanilla jar offline: copy `paper-<v>.jar` together with its `cache/`,
  `versions/<mc>/` and `libraries/` from an existing local server.
- Running the real client needs Linux LWJGL natives. 26.3 uses LWJGL 3.4.3 with SDL; a Windows-built cache only has
  Windows natives, so the 26.3 client could not be launched in the cloud.
- Kill helper processes with anchored patterns (`pgrep -f "^/usr/lib/jvm.*KnotClient"`) or you kill your own shell.

## Gotchas found the hard way

- Paper's `Block.getBlockPower(BlockFace)` misses repeaters and powered blocks; `Signals` calls Minecraft's
  `Level.getSignal` by reflection instead (falls back with a log line if names change).
- The daylight detector recalculates every 20 ticks; `LogicManager.onRedstone` pins it via `BlockRedstoneEvent`.
- Minecraft's GUI font has no ✓ ✕ ≠ ≤ ≥ →: use ASCII in anything drawn on screen or in chat.
- 26.x `EditBox` has no `setFilter`; sanitise in the responder.
- `config.yml` merges new defaults into existing maps (`copyDefaults`), so changing recipe letters can leave an
  inconsistent recipe on an existing server; test with a fresh config.
- In a Fabric 26.x `Screen`, don't call `extractBackground` yourself (crash: "blur once per frame").
