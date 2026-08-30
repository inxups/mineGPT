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

When listening is explicitly requested, call `minegpt_next_message` with
`wait_seconds` set to 45. For each player message, answer concisely, call
`minegpt_reply` with its exact `message_id`, and continue only while the
current conversation remains active.

Use `live-data/SKILL.md` when the player's request needs current Minecraft
state. Do not claim a reply was delivered unless `minegpt_reply` succeeds.

Use `modpack-recipe-investigation/SKILL.md` for how-to-obtain, recipe,
machine-processing, or modpack progression questions. It requires local pack
evidence before an answer is presented as confirmed.
