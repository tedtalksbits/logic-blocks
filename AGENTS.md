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
- `paper-selftest/`: development-only plugin that builds circuits on a real Paper server and checks the redstone,
  including the sorter build from `docs/SORTER.md` (four scenarios side by side, about 2 minutes).
- `ci/` and `.github/workflows/ci.yml`: GitHub Actions (plugin, client, real-Paper self-test). See `docs/CI.md`.

## Status (2026-10-25)

- v1 complete. Minecraft/Paper **26.3** (plugin also runs on 26.2: `api-version` is a minimum).
- Plugin: 17 unit tests pass; the real-server self-test passes on Paper 26.2-130 and 26.3-211.
- Client: 6 unit tests pass. Exercised end to end in real **26.2** and **26.3** games; on 26.3 (client 0.2.0, Paper
  26.3-211) the owner ran every `docs/TEST_PLAN.md` test and all passed.
- CI (GitHub Actions) green from its first run: plugin, client, real-Paper 26.3 self-test.
- Item sorter build (`docs/SORTER.md`, picture `docs/images/sorter.png` drawn by a script, not a game screenshot):
  `/logic demo sorter [items]` builds it (`Demos`, shared with the self-test). Two Logic Blocks per station. Proven by the self-test's
  "sorter" steps on real Paper 26.3 (60 and 190 mixed items, no wrong items, 2.4 items/s, full chest passes items on).
  Limit: feed through one hopper (a double-speed feed let one wrong item through in 68).
- Side labels (plugin 0.2.0, client 0.3.0): the mod draws N, E, S, W over the Logic Block under the crosshair, asking
  the server via `query`/`is`; toggle key N. Each player sets their look in a settings screen with its own preview
  (`/logicblocks` or the editor's Labels button). Unit-tested (19 plugin, 19 client), the self-test passes, and the
  owner ran `docs/TEST_PLAN.md` tests 15-25 in a real 26.3 game: all passed. See `docs/DESIGN.md`, "Side labels".

## Open items

1. Optional: Mod Menu entry for the label settings (its Maven repo isn't in the offline cache).
2. v2 ideas, not started: timers, counters, variables, wireless links between Logic Blocks.

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
| Sides shown by labels the mod draws, not a texture | Owner wants no visual change to any vanilla block; a texture would hit every daylight detector |
| Labels on by default, toggle key N, N/E/S/W only | Owner's call |
| Redstone first. Actions beyond redstone (e.g. moving items) are **open**, if limited and not game-breaking; not decided yet | Owner: the goal is redstone that's intuitive and programming-friendly, not purity; wants guidance on scope |
| Release tag = plugin version; the client keeps its own version; both jars in each release | Same as Blueprints (owner's call) |
| Label look is a per-player client setting with an in-screen preview; no server-wide off switch | Owner: visual preferences belong to the player; players can't look around while in a menu |

## Working rules

- Commit locally; **the owner pushes** to GitHub. Agents commit as `Claude <noreply@anthropic.com>` (pass it per
  commit with `git -c user.name=Claude -c user.email=noreply@anthropic.com`; don't change the repo's git config, the
  owner commits as himself).
- Put anything that needs the owner's decision at the top of your report, clearly marked. Keep reports short; no
  "•" separators.
- Test on a real Paper server before claiming redstone behaviour works. MockBukkit doesn't simulate redstone.

## Building and testing

- Plugin: `gradlew build` at the repo root (Java 25, Gradle 9.8.0 via `gradlew`, same as the client). Jar: `logic-plugin/build/libs/logic-plugin-<v>.jar`.
- Client: `cd fabric-client && gradlew build` (Loom 1.19.2 needs Gradle 9.8.0). Jar:
  `fabric-client/build/libs/logic-blocks-client-<v>.jar`.
- Self-test: `ci/run-selftest.sh <paper.jar> <new-folder>` (Linux, Git Bash, CI) sets up a throwaway flat-world
  server, runs it with `-Dlogic.selftest.exit=true`, and fails unless `plugins/LogicSelfTest/report.txt` ends
  `SELFTEST PASSED`. Offline, add `PAPER_SEED_DIR=<existing server folder>`.
- CI runs all three on every push to `main` (`docs/CI.md`). Releases: tag `v<plugin version>`; the release
  workflow reruns CI and publishes both jars (`docs/CI.md`, "Releases"). No automatic deploy to the live server.
- Dev loop: `run\build-and-install.cmd` (Windows) or `run/build-and-install.sh` runs `run/DevInstall.java`: stops
  the local test server (RCON if enabled, else waits for `stop`), builds and tests both, installs the plugin as
  `plugins/LogicBlocks.jar` and the client in Minecraft's mods folder, offers to start the server. Defaults fit the
  owner (blueprints-mc's test server, JDK and Gradle cache next to this project); `LOGIC_SERVER_DIR`,
  `LOGIC_MODS_DIR`, `LOGIC_GRADLE_ARGS`, `JAVA_HOME` override. Java rather than PowerShell so it runs (and was
  tested, against a real Paper server and RCON) anywhere.

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
