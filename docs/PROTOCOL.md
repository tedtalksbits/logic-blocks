# Client <-> server protocol (v1)

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

**Program** (`Program.encode`): VarInt format (1), VarInt rule count (0-8), for each rule: VarInt condition count
(0-3), each condition = VarInt kind, VarInt side, VarInt op, String id, VarInt number, VarInt number2; then the
action = VarInt kind, VarInt strength, VarInt ticks. Then the "otherwise" action. Enum values are ordinals of
`Condition.Kind`, `Side`, `Condition.Op`, `Action.Kind`; append new values at the end only.

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
- Bump `ClientBridge.PROTOCOL` and `Messages.PROTOCOL` together when a message's bytes change. A new
  `Program.FORMAT` means stored programs need a migration in `Program.decode`.
