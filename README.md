# MineGPT

MineGPT is a client-only Minecraft 1.21.1 chat companion for Fabric and
NeoForge. It does not call the OpenAI API and requires no Minecraft server Mod
or plugin. A local Java Bridge exposes Minecraft messages as MCP tools to the
ChatGPT Desktop app.

```text
Minecraft @ai message -> 127.0.0.1:37832 -> MineGPT Bridge (MCP STDIO)
    -> ChatGPT Desktop -> MCP reply tool -> local Minecraft chat
```

## Requirements

- Minecraft `1.21.1` and Java `21`.
- Either Fabric Loader or NeoForge, matching the selected Mod JAR.
- ChatGPT Desktop with local MCP server support. This is a local Codex-host MCP
  connection, not a ChatGPT web plugin.

## Build

Each component is an independent Gradle project:

```sh
cd fabric
./gradlew build
```

```sh
cd neoforge
./gradlew build
```

```sh
cd bridge
./gradlew installDist
```

Install exactly one of the resulting client Mod JARs:

- `fabric/build/libs/minegpt-fabric-0.1.0-SNAPSHOT.jar`
- `neoforge/build/libs/minegpt-0.1.0-SNAPSHOT.jar`

The Bridge distribution is written to
`bridge/build/install/minegpt-bridge`. Its command is
`bin/minegpt-bridge` on macOS/Linux and `bin/minegpt-bridge.bat` on Windows.

## ChatGPT Desktop Setup

1. Build the Bridge and open ChatGPT Desktop **Settings -> MCP servers**.
2. Add a **STDIO** server named `minegpt`. Set its command to the absolute path
   of the distribution script above; no arguments or API key are required.
3. Restart ChatGPT Desktop, then use `/mcp` to confirm that `minegpt` is
   connected.
4. In a ChatGPT Desktop conversation, ask it to call
   `minegpt_pairing_code`. Copy the returned `token` value.
5. Start Minecraft with the selected client Mod and run:

   ```text
   /minegpt pair <token>
   ```

6. In the same ChatGPT conversation, send this prompt:

   ```text
   Start listening to Minecraft. For every MineGPT player message, answer it,
   call minegpt_reply with the exact message_id, then immediately call
   minegpt_next_message again with wait_seconds 45. Continue until I tell you
   to stop.
   ```

The MCP server also provides these instructions during initialization. The
explicit prompt makes the desired long-running workflow clear to the agent.

On its first start, the Bridge creates a user-editable skills directory and a
default guide without overwriting existing files. The directory is relative to
the **actual game instance run directory reported by the client**:

```text
<instance directory>/.minecraft/minegpt/skills/
```

For the development launchers in this repository that is
`fabric/run/.minecraft/minegpt/skills/` or
`neoforge/run/.minecraft/minegpt/skills/`. With a launcher whose reported game
directory already ends in `.minecraft`, it is that directory's
`minegpt/skills/` child. This
means each Prism, Modrinth, or development instance can have different skills.

Put Markdown skill files in this directory. ChatGPT Desktop does not scan local
folders itself; MineGPT makes the folder available with `minegpt_list_skills()`
and `minegpt_get_skill(name)`. The game client sends its run directory during
the authenticated Bridge handshake, and the server instructions tell ChatGPT
to list and load relevant skills at the start of a MineGPT task. For unusual
launchers, set the client instance's normal game directory; no server setting
is required.

## In-Game Use

- Type `@ai <message>` in the normal Minecraft chat box. MineGPT intercepts it
  locally, so it is never sent to a multiplayer server or other players.
- Use `/minegpt status` to see pairing, Bridge connection, and local pending
  message state.
- ChatGPT replies appear as local `[MineGPT]` system messages. Ordinary chat
  without the `@ai` prefix is unchanged.

The Bridge listens only on `127.0.0.1:37832`, requires a random pairing token,
and stores its token plus up to 200 pending messages for 24 hours in
`~/.minegpt/bridge-state.json`. The Minecraft client stores only its pairing
token in its normal `config/minegpt.json` file.

## MCP Tools And Limits

- `minegpt_status()` returns the Bridge connection and queue state.
- `minegpt_pairing_code()` returns the local host, port, and pairing token.
- `minegpt_list_skills()` lists Markdown skills in the local Minecraft
  `minegpt/skills` directory.
- `minegpt_get_skill(name?)` reads one listed Markdown skill; omitting `name`
  loads the default `minegpt-guide.md`.
- `minegpt_get_chunk_info(chunk_x?, chunk_z?)` returns a read-only snapshot of a
  single chunk already loaded by the client. Omitting both coordinates reads the
  player's current chunk; otherwise provide both chunk coordinates. It returns
  the dimension, game time, build-height range, and 256 surface heights and
  block IDs in row-major `local_z * 16 + local_x` order. It never asks the
  Minecraft server to load a chunk. An unloaded chunk returns `loaded: false`.
- `minegpt_get_player_state()` returns the current player position, dimension,
  health, hunger, experience, and game mode.
- `minegpt_get_target()` returns the block or entity under the crosshair.
- `minegpt_get_inventory()` returns non-empty hotbar, main-inventory, armor,
  and offhand slots with item IDs, counts, and durability; item NBT is omitted.
- `minegpt_get_nearby_entities(radius?)` returns at most 64 client-visible
  entities within a radius of 1--64 blocks (default 32).
- `minegpt_get_block(x, y, z)` returns one already loaded block's ID, state,
  light, and block-entity type. It does not load the containing chunk.
- `minegpt_get_chunk_section(chunk_x, chunk_z, section_y)` returns counts of
  up to 64 block IDs in one already loaded 16 by 16 by 16 section; `section_y`
  is the vertical section coordinate, not a block Y coordinate.
- `minegpt_get_biome_and_environment()` returns the player's biome, world time,
  moon phase, weather, difficulty, and local block/sky light.
- `minegpt_next_message(wait_seconds)` reads the oldest unhandled player
  message and waits no longer than 45 seconds.
- `minegpt_reply(message_id, text)` displays a reply locally in Minecraft.

Minecraft cannot create or wake a ChatGPT conversation. The Bridge can queue
messages while its ChatGPT Desktop process is running but the listening task is
between tool calls. If the Bridge itself is not running, the Mod reports that
the local Bridge is unavailable and retains up to 200 unsent messages in memory
until it reconnects or Minecraft closes.

All read tools are bounded and only access data the local client already knows.
MineGPT does not expose item/block-entity NBT, full chat history, or chunks the
client has not loaded. A disconnected client or unavailable data produces a
structured `available: false` result rather than loading or modifying anything.
