# Changelog

## MCP Tools And Limits

- `minegpt_status()` returns the Bridge connection and queue state.
- `minegpt_pairing_code()` returns the local host, port, and pairing token.
- `minegpt_list_skills()` lists Markdown skills in the local Minecraft
  `minegpt/skills` directory, including nested files up to eight levels deep.
- `minegpt_get_skill(name?)` reads one listed Markdown skill by its relative
  path; omitting `name` loads the default `minegpt-guide.md`.
- `minegpt_import_github_skill(repository, source_path, ref?,
  destination_path?, overwrite?)` downloads one explicitly requested Markdown
  skill from a public GitHub repository into `minegpt/skills/`. `repository` is
  `owner/repository`, `source_path` is a repository-relative `.md` path, and
  `ref` defaults to `main`. By default the file is installed under its source
  filename and will not overwrite an existing skill. Set `overwrite: true` and
  provide `destination_path` only when replacement is intended. Downloads use
  GitHub's fixed raw-content host, allow up to 256 KiB of valid UTF-8 Markdown,
  and require no GitHub account or token; private repositories are unsupported.
- `minegpt_list_game_files(path?, max_depth?)` lists files and directories
  under the active Minecraft instance directory. It accepts only relative paths,
  scans up to eight levels below the requested directory, skips symlinks, and
  returns at most 500 entries. Omit `path` to start at the instance root.
- `minegpt_read_game_file(path, offset?, max_bytes?, encoding?)` reads any
  regular file anywhere under the active instance directory in bounded chunks.
  It returns UTF-8 text or Base64, with a default 64 KiB and maximum 256 KiB per
  call; use `offset` to continue a larger file.
- `minegpt_get_game_options()` parses the active instance's `options.txt`.
- `minegpt_list_installed_mods()` lists Mod JARs in `mods/`, and
  `minegpt_list_saved_worlds()` lists world directories in `saves/`.
- `minegpt_get_recent_log(max_lines?)` returns the tail of `logs/latest.log`,
  up to 1000 lines.
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
