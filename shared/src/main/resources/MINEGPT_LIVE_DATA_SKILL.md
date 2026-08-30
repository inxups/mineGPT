---
name: live-data
description: Read current Minecraft client data through MineGPT MCP tools.
version: 1
---

# Live Minecraft Data

Load this Skill when the player asks about their current Minecraft state. The
MCP tools below are read-only client snapshots. They never load chunks, modify
the world, execute commands, move the player, or send data to a server.

## Tool Selection

- `minegpt_get_player_state`: position, health, hunger, experience, game mode,
  and dimension.
- `minegpt_get_target`: the block or entity under the crosshair.
- `minegpt_get_inventory`: non-empty inventory, armor, offhand, item counts,
  and durability.
- `minegpt_get_nearby_entities`: visible nearby entities; use the smallest
  useful radius, up to 64 blocks.
- `minegpt_get_block`: one exact already-loaded block position.
- `minegpt_get_chunk_info`: 16 by 16 surface summary for one already-loaded
  chunk; omit coordinates for the player chunk.
- `minegpt_get_chunk_section`: block-ID counts for one already-loaded 16 by 16
  by 16 chunk section.
- `minegpt_get_biome_and_environment`: biome, time, weather, difficulty, and
  local light levels.

## Rules

Call only the tools needed to answer the request. Prefer fresh tool results to
the message's older context. State when data is unavailable, a chunk is not
loaded, or an answer would require unseen terrain. Do not infer item NBT,
unloaded chunks, server rules, or information the client cannot see. Keep
replies short enough for the Minecraft chat overlay.
