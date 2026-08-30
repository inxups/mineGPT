# MineGPT

[中文文档](README.zh-CN.md)

Use ChatGPT Desktop as a companion in your Minecraft 1.21.1 game. Type a
question with `@gpt` in Minecraft and receive the reply as a local chat message.
MineGPT is client-only: it does not require a server Mod or plugin, and your
`@gpt` messages are never sent to a multiplayer server or other players.

```text
Minecraft @gpt message -> local MineGPT Bridge -> ChatGPT Desktop -> local reply
```

## Install MineGPT

1. Add the MineGPT Mod JAR to the `mods` folder of the Minecraft instance you
   will play. Fabric users must also install Fabric API in that same instance.
2. Extract the MineGPT Bridge distribution somewhere that will remain available
   on your computer. You will give ChatGPT Desktop the absolute path to its
   launch script:

   - macOS/Linux: `bin/minegpt-bridge`
   - Windows: `bin/minegpt-bridge.bat`

3. Start the selected Minecraft instance once. MineGPT runs only on your client;
   there is nothing to install on the multiplayer server.

## Connect ChatGPT Desktop

1. In ChatGPT Desktop, open **Settings -> MCP servers** and add a **STDIO**
   server named `minegpt`.
2. Set its command to the absolute Bridge script path from the previous section.
   Do not add arguments or an API key.
3. Restart ChatGPT Desktop, then run `/mcp` in a conversation to confirm that
   `minegpt` is connected.
4. In that conversation, ask ChatGPT to call `minegpt_pairing_code`, then copy
   the returned `token`.
5. In Minecraft, run:

   ```text
   /minegpt pair <token>
   ```

6. Tell ChatGPT to listen for your in-game questions. This prompt is a useful
   starting point:

   ```text
   Start listening to Minecraft. For every MineGPT player message, answer it,
   call minegpt_reply with the exact message_id, then immediately call
   minegpt_next_message again with wait_seconds 45. Continue until I tell you
   to stop.
   ```

Keep this ChatGPT conversation open while it is listening. Minecraft cannot
open or wake a conversation on its own.

Leaving a Minecraft world or returning to the title screen keeps the MineGPT
Bridge session open. The Bridge closes only when the Minecraft client exits.

## Use It In Game

- Type `@gpt <message>` in the normal Minecraft chat box. For example:

  ```text
  @gpt What can I craft with the items in my inventory?
  ```

- ChatGPT replies appear as local `[MineGPT]` system messages. Ordinary chat
  without the `@gpt` prefix is unchanged.
- Run `/minegpt status` to check pairing, Bridge connection, and queued-message
  status.
- Run `/minegpt github <github_url>` to install one public GitHub Markdown
  skill into this Minecraft instance. Use a normal GitHub file-page URL or a
  `raw.githubusercontent.com` URL. Existing skills are never overwritten.

## Add Skills

MineGPT creates a user-editable skill folder for each Minecraft instance:

```text
<game run directory>/minegpt/skills/
```

For most launchers, the game run directory is that instance's `.minecraft`
folder. This keeps skills separate between Prism, Modrinth, and other instances.
Add Markdown files directly or in subfolders, such as
`building/redstone/guide.md`. Paths may be up to eight folders deep and each
file may be up to 256 KiB.

The built-in `minegpt-guide.md`, `live-data/SKILL.md`, and
`modpack-recipe-investigation/SKILL.md` are restored if deleted.
`live-data/SKILL.md` selects read-only tools for player state, inventory,
entities, blocks, chunks, and environment data. The modpack recipe skill makes
ChatGPT investigate the current instance's local datapacks, KubeJS, configs,
FTB Quests files, and relevant Mod JAR resources before presenting a crafting
or progression route as confirmed. Your own skill files are never overwritten
or regenerated.

## Privacy, Safety, And Limits

MineGPT listens only on `127.0.0.1:37832` and pairs your game with ChatGPT
Desktop through a random token. The Bridge keeps its token and up to 200 pending
messages for 24 hours in `~/.minegpt/bridge-state.json`; the Minecraft client
stores only its pairing token in `config/minegpt.json`.

The connected ChatGPT conversation can inspect bounded, read-only information
from the active Minecraft client. It cannot run Minecraft commands, move the
player, modify the world, interact with a server, load new chunks, or access
full chat history, item/block-entity NBT, or chunks your client has not loaded.
Game-file access is limited to the paired instance directory and paths outside
it are rejected.

If ChatGPT is between tool calls, the Bridge queues messages. If the Bridge is
unavailable, the Mod reports the problem and keeps up to 200 unsent messages in
memory until it reconnects or Minecraft closes.

For the complete MCP tool reference, see [CHANGELOG.md](CHANGELOG.md).

## Build From Source

You only need these steps when a built Mod JAR and Bridge distribution are not
available. Build the Bridge and the one client Mod you plan to use:

```sh
cd bridge
./gradlew installDist
```

```sh
cd fabric
./gradlew build
```

Or, for NeoForge:

```sh
cd neoforge
./gradlew build
```

The Bridge is written to `bridge/build/install/minegpt-bridge`. The Mod JAR is
written to the selected project's `build/libs` directory.
