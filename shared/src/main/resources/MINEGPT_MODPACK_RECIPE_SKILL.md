---
name: modpack-recipe-investigation
description: Investigate how to obtain an item or block from this Minecraft instance's local modpack data.
version: 1
---

# Modpack Recipe Investigation

Use this skill when a player asks how to obtain an item, block, machine, or
resource in a modded Minecraft instance, especially in a skyblock pack where
normal world generation may be unavailable.

## Source Of Truth

Treat the active instance's files and installed Mods as authoritative. Do not
replace missing local evidence with a recipe remembered from an upstream Mod.
Clearly separate confirmed local data, upstream behavior, and inference.

## Investigation Order

1. Resolve the exact item or block ID. If the name is ambiguous, use the
   player's JEI result or ask for the displayed item before guessing.
2. Call `minegpt_search_modpack_files` with the ID and `scope: "recipes"`.
   It checks likely datapack, recipe, defaultconfig, global-pack, and local
   world datapack locations for literal matches, including custom recipe types.
3. Call the same tool with `scope: "kubejs"` for KubeJS `server_scripts`,
   `startup_scripts`, and data. Search the ID, recipe type, relevant tags, and
   aliases as needed.
4. Call it with `scope: "quests"` for FTB Quests and related local files. A
   quest reward or prerequisite can be the intended early-game route even when
   mining or crafting is technically possible.
5. When configuration does not explain the result, first call
   `minegpt_list_installed_mods`, then call `minegpt_inspect_mod_jar` for one
   suspected direct Mod JAR. It reads bounded Mod metadata, resource text, and
   printable class strings without executing or decompiling code. Search for
   recipe types, item IDs, tags, machine names, fluid names, or registered
   serializer names. If it has no local evidence, say so rather than claiming
   bytecode was analysed.

## Cross-Checks

- Compare the discovered route with JEI in the player's client when possible.
- Verify inputs, outputs, fluids, amounts, chance, processing interval, and
  placement or orientation requirements before giving a route.
- For skyblock questions, distinguish real world-generation ore from a
  generated-resource route. State whether a catalyst, machine, fluid, or quest
  reward is required first.
- If no local evidence is available, state what locations were checked and mark
  the remaining answer as uncertain instead of inventing a recipe.

## Player-Facing Answer

Answer in the player's language and keep the chat response concise. Lead with
the actionable route, include exact IDs only when they disambiguate the steps,
and briefly state confidence or evidence when the route is easy to confuse with
upstream documentation.
