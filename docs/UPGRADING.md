# Upgrading to a new Minecraft version

Checklist for moving Logic Blocks to a new release. Currently on 26.3 (moved from 26.2 with no code changes:
version pins only, Loom 1.19.2 with Gradle 9.8.0 for the client). Same shape as Blueprints' `docs/UPGRADING.md`.

## What breaks

| Part | Depends on | On a new version |
|---|---|---|
| Rule model (`logic-plugin/.../model/`) | nothing | Never changes for a version bump |
| Plugin (`logic-plugin/`) | Paper API, plus **two Minecraft internals by name** (below) | Usually runs unchanged; rebuild and run the self-test |
| Client (`fabric-client/`) | Minecraft's GUI and networking classes | Needs a rebuild every minor version; fix compile errors in `LogicScreen`, `LogicClient`, `net/Net`, `net/RawPayload` |

**Minecraft internals the plugin uses** (both checked by the self-test):

1. `Signals`: `CraftWorld.getHandle()`, `Level.getSignal(BlockPos, Direction)`, `new BlockPos(int, int, int)`,
   `Direction.NORTH...`, by reflection. If they move, the plugin logs "Exact per-side redstone reading is
   unavailable" and falls back to Paper's `getBlockPower(face)`, which misses repeaters. The self-test step
   "sides: north input -> 5" fails in that case. Fix the names in `Signals.init`.
2. The daylight detector's sky-light update must keep firing `BlockRedstoneEvent` (`LogicManager.onRedstone`). The
   self-test steps "daylight ignored" fail if it stops.

## Where versions are pinned

| File | Setting |
|---|---|
| `logic-plugin/build.gradle.kts` | `paper-api`, MockBukkit (`mockbukkit-v26.3`, named for the Minecraft version) |
| `logic-plugin/src/main/resources/plugin.yml` | `api-version` (a minimum) |
| `paper-selftest/build.gradle.kts` | `paper-api` |
| `fabric-client/gradle.properties` | `minecraft_version`, `loader_version`, `loom_version`, `fabric_api_version` |
| `fabric-client/src/main/resources/fabric.mod.json` | `"minecraft": "~26.3"` |

## Steps

1. Update the plugin pins; `gradle build` (unit tests).
2. Run the self-test: copy `logic-plugin/build/libs/logic-plugin-*.jar` and
   `paper-selftest/build/libs/paper-selftest-*.jar` into a throwaway Paper server (flat world) and start it with
   `java -Dlogic.selftest.exit=true -jar paper-<version>.jar --nogui`. It builds circuits around Logic Blocks,
   checks what the redstone actually does, writes `plugins/LogicSelfTest/report.txt`, and stops. It must end with
   `SELFTEST PASSED`.
3. Update the client pins; `cd fabric-client && gradlew build`; fix compile errors (GUI classes move between
   releases; see Blueprints' `docs/UPGRADING.md` for the 26.x names). The client has no mixins.
4. Walk `docs/TEST_PLAN.md` tests 2-4 and 6 in a real client: the editor opens, live readings update, saving changes
   the output.
5. If a new version adds blocks or items, nothing needs changing: ids are checked against the game's registries.

## Protocol and stored data

Bump `ClientBridge.PROTOCOL` and `Messages.PROTOCOL` together only when message bytes change. Stored programs are
versioned by `Program.FORMAT` and `LogicNode.RECORD_FORMAT`; changing either needs a migration path in the decoder,
because programs live in players' worlds and items.
