---
name: minegpt
description: Guide for helping a player through MineGPT's read-only Minecraft MCP tools.
version: 1
---

# MineGPT Skill

MineGPT connects this conversation to one locally running Minecraft client.
All game-data tools are read-only snapshots from chunks and entities the client
already knows. They cannot load chunks, modify the world, run Minecraft
commands, move the player, or interact with a multiplayer server.

## Start And Pairing

- Use `minegpt_status` to check whether the local Minecraft client is connected.
- Use `minegpt_pairing_code` only when the player asks to pair a Minecraft
  client. Treat the returned token as a local secret; do not repeat it unless
  it is needed for pairing.
- When the user asks how MineGPT works or asks for these rules, call
  `minegpt_get_skill` and use the returned document as the source of truth.

## Minecraft Chat Workflow

When listening is explicitly requested, call `minegpt_next_message` with
`wait_seconds` set to 45. For each `player_message`:

1. Read its `text` and initial `context`.
2. Call a live read tool only when it will materially improve the answer.
3. Answer the player concisely and use `minegpt_reply` with the exact
   `message_id`.
4. Do not claim the reply was delivered unless `minegpt_reply` succeeds.
5. Continue listening only while the current conversation remains active.

The Minecraft client cannot wake this conversation or create a new ChatGPT
turn. A long-poll listener may end when the ChatGPT task ends.

## Choosing Read Tools

- Use `minegpt_get_player_state` for current position, health, hunger,
  experience, mode, or dimension.
- Use `minegpt_get_target` when the player asks about what they are looking at.
- Use `minegpt_get_inventory` for crafting, equipment, supplies, or durability
  questions. It returns item IDs and counts, not item NBT.
- Use `minegpt_get_nearby_entities` for immediate threats or nearby mobs. Keep
  the radius no larger than necessary; the server caps it at 64 blocks.
- Use `minegpt_get_block` for one exact block position. It only works when its
  chunk is loaded locally.
- Use `minegpt_get_chunk_info` for terrain and surface information in one
  loaded chunk. Omit coordinates for the player chunk.
- Use `minegpt_get_chunk_section` for block composition in one loaded 16 x 16
  x 16 section. It returns block counts, not every block state.
- Use `minegpt_get_biome_and_environment` for biome, weather, time, light, and
  difficulty.

## Answer Quality And Boundaries

- Prefer the player's current live data over an older message context when the
  answer depends on a changing situation.
- State uncertainty when data is unavailable, stale, or outside loaded chunks.
- Do not invent unseen terrain, inventories, entities, recipes, or server rules.
- Never say that MineGPT changed the game. It has no write or command tools.
- Keep in-game replies short enough to read in the Minecraft chat overlay.
