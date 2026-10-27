# Client <-> server protocol (v2)

Plugin messages on `logicblocks:*` channels. Encoding is Minecraft's `FriendlyByteBuf` style: VarInt, big-endian
Int32, Bool (1 byte), String (VarInt byte length + UTF-8), Bytes (VarInt length + raw). Both sides use
`dev.whizzle.logic.model.Wire`.

| Channel | Direction | Body |
|---|---|---|
| `logicblocks:hello` | C->S | VarInt protocol |
| `logicblocks:welcome` | S->C | VarInt protocol |
| `logicblocks:open` | S->C | Int32 x, y, z, Bool editable, String owner name, Bytes program, Bytes snapshot |
| `logicblocks:live` | S->C | Int32 x, y, z, Bytes snapshot |
| `logicblocks:save` | C->S | Int32 x, y, z, Bytes program |
| `logicblocks:result` | S->C | Bool ok, String message |
| `logicblocks:close` | C->S | (empty) |
| `logicblocks:query` | C->S | Int32 x, y, z (since plugin 0.2.0 / client 0.3.0) |
| `logicblocks:is` | S->C | Int32 x, y, z, Bool is a Logic Block |

Protocol 2 (plugin 0.3.0, client 0.4.0) carries program format 2; protocol 1 carried format 1.

**Program** (`Program.encode`, format 2): VarInt format (2), the output rules, the "otherwise" action, the "also"
rules. Rules: VarInt count (0-8), for each rule: VarInt condition count (0-3), each condition = VarInt kind, VarInt
side, VarInt op, String id, VarInt number, VarInt number2; then VarInt action count (1-3), each action = VarInt kind,
VarInt strength, VarInt ticks, String id, VarInt from side, VarInt to side. Enum values are ordinals of
`Condition.Kind`, `Side`, `Condition.Op`, `Action.Kind`; append new values at the end only.

Format 1 (v1): VarInt format (1), rules as above but each with exactly one action written as VarInt kind, VarInt
strength, VarInt ticks; then the "otherwise" action in the same short form; no "also" rules. `Program.decode` reads
both, for blocks and items saved by v1.

**Snapshot** (`Snapshot.encode`): for each side in order north, east, south, west, up, down: String block id,
VarInt signal, Bool has container, and if so VarInt n + n x (String item id, VarInt count). Then VarInt player
count + each distance in tenths of a block, VarInt current output, VarInt (matched rule + 1).

## Behaviour

- The client sends `hello` once the server announces the channels; a different protocol number makes the client
  refuse to open the editor and say the mod needs updating.
- Right-clicking a Logic Block (not sneaking with a block in hand) sends `open` if the player's client listens on
  `logicblocks:open`, otherwise the rules in chat. `editable` is false for players who may only look.
- After `open`, the server sends `live` every 4 ticks until the client sends `close`, the player moves more than
  16 blocks away, the block disappears, or the player leaves.
- `save` is checked for ownership (`editing` setting, `logicblocks.admin`), reach (8 blocks), and with
  `Program.problem()`; the answer is `result`. Each player may send at most 10 requests per second.
- `query` asks whether the block at x, y, z (in the player's world) is a Logic Block. The client only asks about a
  daylight detector under its crosshair, at most once a second per block while waiting and again after 2 seconds
  while still looking, and draws N, E, S, W while the answer is yes. The server answers with `is` for loaded blocks
  within 16 blocks of the player and stays silent otherwise. Queries have their own limit (20 per second per
  player), so they never use up the budget for `save`. Anyone may ask; the answer reveals nothing a player can't see
  by right-clicking.
- `query`/`is` were added without bumping the protocol: the client checks that the server listens on
  `logicblocks:query` before asking, and an older client never asks. Older pairs keep working without labels.
- Bump `ClientBridge.PROTOCOL` and `Messages.PROTOCOL` together when a message's bytes change. A new
  `Program.FORMAT` means stored programs need a migration in `Program.decode`.
