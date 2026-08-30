package com.inxups.minegpt.bridge;

import com.inxups.minegpt.shared.PlayerMessage;
import com.inxups.minegpt.shared.ChunkInfo;
import com.inxups.minegpt.shared.ChunkQuery;
import com.inxups.minegpt.shared.GameQuery;
import com.inxups.minegpt.shared.GameQueryResult;
import com.inxups.minegpt.shared.MineGPTVersion;
import com.inxups.minegpt.shared.ProtocolCodec;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registers the MCP tools used by the ChatGPT Desktop listening conversation. */
final class MineGPTMcpServer {
    private final LoopbackBridgeServer bridge;
    private final SkillStore skills;
    private final GameDirectoryStore gameFiles;
    private final GitHubSkillImporter githubSkills;

    MineGPTMcpServer(LoopbackBridgeServer bridge) {
        this(bridge, new SkillStore());
    }

    MineGPTMcpServer(LoopbackBridgeServer bridge, SkillStore skills) {
        this.bridge = bridge;
        this.skills = skills;
        this.gameFiles = new GameDirectoryStore(skills);
        this.githubSkills = new GitHubSkillImporter(skills);
    }

    McpSyncServer start() {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        return McpServer.sync(transport)
                .serverInfo("minegpt", MineGPTVersion.current())
                .instructions(instructions())
                .toolCall(tool("minegpt_status", "Return MineGPT bridge connection and queue status.", Map.of()),
                        (exchange, request) -> success(ProtocolCodec.toJson(bridge.status())))
                .toolCall(tool("minegpt_pairing_code", "Return the one-time pairing token for the Minecraft client.", Map.of()),
                        (exchange, request) -> success(ProtocolCodec.toJson(Map.of(
                                "host", "127.0.0.1",
                                "port", bridge.port(),
                                "token", bridgeToken()))))
                .toolCall(tool("minegpt_list_skills", "List user-editable MineGPT Markdown skills, up to eight nested directories deep, from the local Minecraft minegpt/skills directory.", Map.of()),
                        (exchange, request) -> listSkills())
                .toolCall(tool("minegpt_get_skill", "Read one Markdown skill from the local Minecraft minegpt/skills directory. Call minegpt_list_skills first and pass its relative path.", skillSchema()),
                        (exchange, request) -> getSkill(request.arguments()))
                .toolCall(tool("minegpt_import_github_skill", "Download one explicitly requested Markdown skill from a public GitHub repository into the local Minecraft minegpt/skills directory. Existing files are not overwritten unless overwrite is true.", githubSkillSchema()),
                        (exchange, request) -> importGitHubSkill(request.arguments()))
                .toolCall(tool("minegpt_list_game_files", "Recursively list files and directories in the active Minecraft game directory without leaving it. Results are capped at 500 entries.", gameFileListSchema()),
                        (exchange, request) -> listGameFiles(request.arguments()))
                .toolCall(tool("minegpt_read_game_file", "Read a bounded byte range from a regular file in the active Minecraft game directory as UTF-8 text or Base64.", gameFileReadSchema()),
                        (exchange, request) -> readGameFile(request.arguments()))
                .toolCall(tool("minegpt_search_modpack_files", "Search bounded text files in likely recipe, KubeJS, config, datapack, and FTB Quests locations of the active instance. This is literal, read-only local evidence only.", modpackSearchSchema()),
                        (exchange, request) -> searchModpackFiles(request.arguments()))
                .toolCall(tool("minegpt_inspect_mod_jar", "Inspect one direct mods/*.jar file without executing it. Returns bounded Mod metadata plus matching recipe/resource text or printable class strings.", modJarInspectionSchema()),
                        (exchange, request) -> inspectModJar(request.arguments()))
                .toolCall(tool("minegpt_get_game_options", "Read and parse the active instance's options.txt client settings.", Map.of()),
                        (exchange, request) -> gameOptions())
                .toolCall(tool("minegpt_list_installed_mods", "List .jar files directly in the active instance's mods directory.", Map.of()),
                        (exchange, request) -> installedMods())
                .toolCall(tool("minegpt_list_saved_worlds", "List direct child world directories in the active instance's saves directory.", Map.of()),
                        (exchange, request) -> savedWorlds())
                .toolCall(tool("minegpt_get_recent_log", "Read the tail of logs/latest.log from the active instance, up to 1000 lines.", recentLogSchema()),
                        (exchange, request) -> recentLog(request.arguments()))
                .toolCall(tool("minegpt_get_chunk_info", "Read a 16 by 16 surface summary of one chunk already loaded by the Minecraft client. Omit both coordinates for the player's current chunk. This never loads a chunk.", chunkSchema()),
                        (exchange, request) -> chunkInfo(request.arguments()))
                .toolCall(tool("minegpt_get_player_state", "Read the player's current position, health, hunger, experience, game mode, and dimension.", Map.of()),
                        (exchange, request) -> gameQuery(GameQuery.playerState()))
                .toolCall(tool("minegpt_get_target", "Read the block or entity under the player's crosshair, if any.", Map.of()),
                        (exchange, request) -> gameQuery(GameQuery.target()))
                .toolCall(tool("minegpt_get_inventory", "Read non-empty player inventory, armor, and offhand slots without item NBT.", Map.of()),
                        (exchange, request) -> gameQuery(GameQuery.inventory()))
                .toolCall(tool("minegpt_get_nearby_entities", "Read up to 64 client-visible entities near the player. Radius defaults to 32 and is limited to 64 blocks.", radiusSchema()),
                        (exchange, request) -> nearbyEntities(request.arguments()))
                .toolCall(tool("minegpt_get_block", "Read one loaded block's ID, state, light, and block-entity type. This never loads a chunk.", blockSchema()),
                        (exchange, request) -> block(request.arguments()))
                .toolCall(tool("minegpt_get_chunk_section", "Read block-ID counts for one loaded 16 by 16 by 16 chunk section. This is a summary, not a full block dump.", chunkSectionSchema()),
                        (exchange, request) -> chunkSection(request.arguments()))
                .toolCall(tool("minegpt_get_biome_and_environment", "Read the player's current biome, time, weather, difficulty, and local light levels.", Map.of()),
                        (exchange, request) -> gameQuery(GameQuery.environment()))
                .toolCall(tool("minegpt_next_message", "Wait up to 45 seconds for the next Minecraft player message.", waitSchema()),
                        (exchange, request) -> nextMessage(request.arguments()))
                .toolCall(tool("minegpt_reply", "Send a reply to an outstanding Minecraft player message.", replySchema()),
                        (exchange, request) -> reply(request.arguments()))
                .build();
    }

    private String bridgeToken() {
        // Only the MCP host can invoke this local STDIO tool; the socket itself never exposes its token.
        return MineGPTBridgeMain.token();
    }

    private String instructions() {
        return "MineGPT bridges a local Minecraft client. The active game directory is available through read-only file tools after the client handshake. "
                + "User-editable Markdown skills live in <active game run directory>/minegpt/skills and are optional workflows: call minegpt_list_skills only when a relevant skill is needed. "
                + "For a request requiring current player, inventory, entity, block, chunk, biome, weather, or light data, load live-data/SKILL.md through minegpt_get_skill before choosing the corresponding read-only game-data tool. "
                + "For a modpack item, block, recipe, machine, or progression question, load modpack-recipe-investigation/SKILL.md before searching local pack evidence. "
                + "Only call minegpt_import_github_skill when the user explicitly asks to install a skill from a public GitHub repository. "
                + "Game-data tools are read-only snapshots of client-visible data: they cannot load chunks from a server or modify the game.";
    }

    private McpSchema.CallToolResult listSkills() {
        try {
            return success(ProtocolCodec.toJson(Map.of(
                    "skills_directory", skills.directory() == null ? "waiting_for_minecraft_client" : skills.directory().toString(),
                    "skills", skills.list())));
        } catch (Exception exception) {
            return failure("Could not list MineGPT skills: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult getSkill(Map<String, Object> arguments) {
        String name = stringArgument(arguments, "name");
        if (name == null || name.isBlank()) {
            name = SkillStore.DEFAULT_SKILL_FILE;
        }
        try {
            return success(skills.read(name));
        } catch (Exception exception) {
            return failure("Could not read MineGPT skill: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult importGitHubSkill(Map<String, Object> arguments) {
        String repository = stringArgument(arguments, "repository");
        String sourcePath = stringArgument(arguments, "source_path");
        String ref = stringArgument(arguments, "ref");
        String destinationPath = stringArgument(arguments, "destination_path");
        if (repository == null || sourcePath == null || !isOptionalString(arguments, "repository")
                || !isOptionalString(arguments, "source_path") || !isOptionalString(arguments, "ref")
                || !isOptionalString(arguments, "destination_path") || !isOptionalBoolean(arguments, "overwrite")) {
            return failure("repository and source_path are required strings; ref and destination_path must be strings; overwrite must be a boolean.");
        }
        boolean overwrite = booleanArgument(arguments, "overwrite", false);
        try {
            return success(ProtocolCodec.toJson(githubSkills.importSkill(
                    repository, sourcePath, ref, destinationPath, overwrite)));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("GitHub skill download was interrupted.");
        } catch (Exception exception) {
            return failure("Could not import GitHub skill: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult listGameFiles(Map<String, Object> arguments) {
        if (!isOptionalInteger(arguments, "max_depth")) {
            return failure("max_depth must be an integer when provided.");
        }
        String path = stringArgument(arguments, "path");
        if (!isOptionalString(arguments, "path")) {
            return failure("path must be a string when provided.");
        }
        int depth = optionalIntegerArgument(arguments, "max_depth") == null ? 3
                : optionalIntegerArgument(arguments, "max_depth");
        try {
            return success(ProtocolCodec.toJson(gameFiles.list(path, depth)));
        } catch (Exception exception) {
            return failure("Could not list Minecraft game files: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult readGameFile(Map<String, Object> arguments) {
        String path = stringArgument(arguments, "path");
        if (path == null || path.isBlank() || !isOptionalString(arguments, "path")
                || !isOptionalLong(arguments, "offset") || !isOptionalInteger(arguments, "max_bytes")
                || !isOptionalString(arguments, "encoding")) {
            return failure("path is required; offset and max_bytes must be integers; encoding must be a string.");
        }
        long offset = optionalLongArgument(arguments, "offset") == null ? 0L : optionalLongArgument(arguments, "offset");
        Integer maxBytes = optionalIntegerArgument(arguments, "max_bytes");
        String encoding = stringArgument(arguments, "encoding");
        try {
            return success(ProtocolCodec.toJson(gameFiles.read(path, offset, maxBytes, encoding)));
        } catch (Exception exception) {
            return failure("Could not read Minecraft game file: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult searchModpackFiles(Map<String, Object> arguments) {
        String query = stringArgument(arguments, "query");
        String scope = stringArgument(arguments, "scope");
        if (query == null || query.isBlank() || !isOptionalString(arguments, "query") || !isOptionalString(arguments, "scope")) {
            return failure("query is required and scope must be a string when provided.");
        }
        try {
            return success(ProtocolCodec.toJson(gameFiles.searchModpackFiles(query, scope)));
        } catch (Exception exception) {
            return failure("Could not search active modpack files: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult inspectModJar(Map<String, Object> arguments) {
        String jarName = stringArgument(arguments, "jar_name");
        String query = stringArgument(arguments, "query");
        if (jarName == null || jarName.isBlank() || !isOptionalString(arguments, "jar_name")
                || !isOptionalString(arguments, "query")) {
            return failure("jar_name is required and query must be a string when provided.");
        }
        try {
            return success(ProtocolCodec.toJson(gameFiles.inspectModJar(jarName, query)));
        } catch (Exception exception) {
            return failure("Could not inspect active Mod JAR: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult gameOptions() {
        try {
            return success(ProtocolCodec.toJson(gameFiles.options()));
        } catch (Exception exception) {
            return failure("Could not read Minecraft options: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult installedMods() {
        try {
            return success(ProtocolCodec.toJson(gameFiles.installedMods()));
        } catch (Exception exception) {
            return failure("Could not list installed Mods: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult savedWorlds() {
        try {
            return success(ProtocolCodec.toJson(gameFiles.savedWorlds()));
        } catch (Exception exception) {
            return failure("Could not list saved Minecraft worlds: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult recentLog(Map<String, Object> arguments) {
        if (!isOptionalInteger(arguments, "max_lines")) {
            return failure("max_lines must be an integer when provided.");
        }
        int maxLines = optionalIntegerArgument(arguments, "max_lines") == null ? 200
                : optionalIntegerArgument(arguments, "max_lines");
        try {
            return success(ProtocolCodec.toJson(gameFiles.recentLog(maxLines)));
        } catch (Exception exception) {
            return failure("Could not read the Minecraft log: " + exception.getMessage());
        }
    }

    private McpSchema.CallToolResult nextMessage(Map<String, Object> arguments) {
        int waitSeconds = numberArgument(arguments, "wait_seconds", 45);
        if (waitSeconds < 1 || waitSeconds > 45) {
            return failure("wait_seconds must be between 1 and 45.");
        }
        try {
            Optional<PlayerMessage> message = bridge.nextMessage(Duration.ofSeconds(waitSeconds));
            return success(message.<String>map(MineGPTMcpServer::toMcpPlayerMessage).orElse("{\"status\":\"idle\"}"));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure("MineGPT listener was interrupted.");
        }
    }

    private McpSchema.CallToolResult reply(Map<String, Object> arguments) {
        String messageId = stringArgument(arguments, "message_id");
        String text = stringArgument(arguments, "text");
        if (messageId == null || text == null || text.isBlank() || text.length() > 4_096) {
            return failure("message_id and a reply text of at most 4096 characters are required.");
        }
        return bridge.reply(messageId, text) ? success("Reply delivered to Minecraft.")
                : failure("Minecraft is disconnected or the message is no longer pending.");
    }

    private McpSchema.CallToolResult chunkInfo(Map<String, Object> arguments) {
        Integer chunkX = optionalIntegerArgument(arguments, "chunk_x");
        Integer chunkZ = optionalIntegerArgument(arguments, "chunk_z");
        if (!isOptionalInteger(arguments, "chunk_x") || !isOptionalInteger(arguments, "chunk_z")) {
            return failure("chunk_x and chunk_z must be integers when provided.");
        }
        if ((chunkX == null) != (chunkZ == null)) {
            return failure("Provide both chunk_x and chunk_z, or neither to use the player chunk.");
        }
        if (chunkX != null && (Math.abs((long) chunkX) > 1_875_000 || Math.abs((long) chunkZ) > 1_875_000)) {
            return failure("Chunk coordinates are outside Minecraft's world border.");
        }
        ChunkInfo info = bridge.readChunkInfo(new ChunkQuery(chunkX, chunkZ));
        return success(ProtocolCodec.toJson(info));
    }

    private McpSchema.CallToolResult nearbyEntities(Map<String, Object> arguments) {
        if (!isOptionalInteger(arguments, "radius")) {
            return failure("radius must be an integer.");
        }
        Integer requestedRadius = optionalIntegerArgument(arguments, "radius");
        int radius = requestedRadius == null ? 32 : requestedRadius;
        if (radius < 1 || radius > 64) {
            return failure("radius must be between 1 and 64.");
        }
        return gameQuery(GameQuery.nearbyEntities(radius));
    }

    private McpSchema.CallToolResult block(Map<String, Object> arguments) {
        Integer x = requiredIntegerArgument(arguments, "x");
        Integer y = requiredIntegerArgument(arguments, "y");
        Integer z = requiredIntegerArgument(arguments, "z");
        if (x == null || y == null || z == null) {
            return failure("x, y, and z must be integers.");
        }
        if (!isBlockCoordinate(x) || !isBlockCoordinate(z)) {
            return failure("x and z are outside Minecraft's world border.");
        }
        return gameQuery(GameQuery.block(x, y, z));
    }

    private McpSchema.CallToolResult chunkSection(Map<String, Object> arguments) {
        Integer chunkX = requiredIntegerArgument(arguments, "chunk_x");
        Integer chunkZ = requiredIntegerArgument(arguments, "chunk_z");
        Integer sectionY = requiredIntegerArgument(arguments, "section_y");
        if (chunkX == null || chunkZ == null || sectionY == null) {
            return failure("chunk_x, chunk_z, and section_y must be integers.");
        }
        if (Math.abs((long) chunkX) > 1_875_000 || Math.abs((long) chunkZ) > 1_875_000) {
            return failure("Chunk coordinates are outside Minecraft's world border.");
        }
        return gameQuery(GameQuery.chunkSection(chunkX, chunkZ, sectionY));
    }

    private McpSchema.CallToolResult gameQuery(GameQuery query) {
        GameQueryResult result = bridge.readGameQuery(query);
        if (result.available() && result.dataJson() != null) {
            return success(result.dataJson());
        }
        return success(ProtocolCodec.toJson(Map.of(
                "available", false,
                "detail", result.detail() == null ? "Minecraft did not provide this data." : result.detail())));
    }

    private static int numberArgument(Map<String, Object> arguments, String name, int defaultValue) {
        if (arguments == null || !(arguments.get(name) instanceof Number number)) {
            return defaultValue;
        }
        return number.intValue();
    }

    private static String stringArgument(Map<String, Object> arguments, String name) {
        if (arguments == null || !(arguments.get(name) instanceof String value)) {
            return null;
        }
        return value;
    }

    private static boolean isOptionalString(Map<String, Object> arguments, String name) {
        return arguments == null || !arguments.containsKey(name) || arguments.get(name) == null
                || arguments.get(name) instanceof String;
    }

    private static boolean isOptionalBoolean(Map<String, Object> arguments, String name) {
        return arguments == null || !arguments.containsKey(name) || arguments.get(name) == null
                || arguments.get(name) instanceof Boolean;
    }

    private static boolean booleanArgument(Map<String, Object> arguments, String name, boolean defaultValue) {
        if (arguments == null || !(arguments.get(name) instanceof Boolean value)) {
            return defaultValue;
        }
        return value;
    }

    private static boolean isOptionalInteger(Map<String, Object> arguments, String name) {
        if (arguments == null || !arguments.containsKey(name) || arguments.get(name) == null) {
            return true;
        }
        if (!(arguments.get(name) instanceof Number number)) {
            return false;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value == Math.rint(value)
                && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    private static Integer optionalIntegerArgument(Map<String, Object> arguments, String name) {
        if (arguments == null || !(arguments.get(name) instanceof Number number)) {
            return null;
        }
        return number.intValue();
    }

    private static Integer requiredIntegerArgument(Map<String, Object> arguments, String name) {
        if (arguments == null || !arguments.containsKey(name) || !isOptionalInteger(arguments, name)) {
            return null;
        }
        return optionalIntegerArgument(arguments, name);
    }

    private static boolean isOptionalLong(Map<String, Object> arguments, String name) {
        if (arguments == null || !arguments.containsKey(name) || arguments.get(name) == null) {
            return true;
        }
        if (!(arguments.get(name) instanceof Number number)) {
            return false;
        }
        double value = number.doubleValue();
        return Double.isFinite(value) && value == Math.rint(value)
                && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE;
    }

    private static Long optionalLongArgument(Map<String, Object> arguments, String name) {
        if (arguments == null || !(arguments.get(name) instanceof Number number)) {
            return null;
        }
        return number.longValue();
    }

    private static boolean isBlockCoordinate(int coordinate) {
        return Math.abs((long) coordinate) <= 29_999_984;
    }

    private static McpSchema.Tool tool(String name, String description, Map<String, Object> properties) {
        return McpSchema.Tool.builder(name, Map.of(
                        "type", "object",
                        "properties", properties,
                        "additionalProperties", false))
                .description(description)
                .build();
    }

    private static Map<String, Object> waitSchema() {
        return Map.of("wait_seconds", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 45,
                "description", "Maximum seconds to wait for one player message."));
    }

    private static Map<String, Object> chunkSchema() {
        return Map.of(
                "chunk_x", Map.of("type", "integer", "description", "Chunk X coordinate. Omit with chunk_z to use the player chunk."),
                "chunk_z", Map.of("type", "integer", "description", "Chunk Z coordinate. Omit with chunk_x to use the player chunk."));
    }

    private static Map<String, Object> skillSchema() {
        return Map.of("name", Map.of(
                "type", "string",
                "description", "Exact relative Markdown path returned by minegpt_list_skills; omit to load minegpt-guide.md."));
    }

    private static Map<String, Object> githubSkillSchema() {
        return Map.of(
                "repository", Map.of("type", "string", "description", "Required public GitHub repository in owner/repository form."),
                "source_path", Map.of("type", "string", "description", "Required relative path to one Markdown file in the repository."),
                "ref", Map.of("type", "string", "description", "Optional simple branch or tag name. Defaults to main."),
                "destination_path", Map.of("type", "string", "description", "Optional relative Markdown path under minegpt/skills. Defaults to the source filename."),
                "overwrite", Map.of("type", "boolean", "description", "Set true only to replace an existing skill. Defaults to false."));
    }

    private static Map<String, Object> gameFileListSchema() {
        return Map.of(
                "path", Map.of("type", "string", "description", "Optional relative directory path. Omit for the game directory root."),
                "max_depth", Map.of("type", "integer", "minimum", 1, "maximum", GameDirectoryStore.MAX_LIST_DEPTH,
                        "description", "Directory levels to scan below path. Defaults to 3."));
    }

    private static Map<String, Object> gameFileReadSchema() {
        return Map.of(
                "path", Map.of("type", "string", "description", "Required relative file path returned by minegpt_list_game_files."),
                "offset", Map.of("type", "integer", "minimum", 0, "description", "Byte offset. Defaults to zero."),
                "max_bytes", Map.of("type", "integer", "minimum", 1, "maximum", GameDirectoryStore.MAX_READ_BYTES,
                        "description", "Maximum bytes to return. Defaults to 65536."),
                "encoding", Map.of("type", "string", "enum", List.of("utf8", "base64"), "description", "Defaults to utf8."));
    }

    private static Map<String, Object> modpackSearchSchema() {
        return Map.of(
                "query", Map.of("type", "string", "minLength", 1, "maxLength", GameDirectoryStore.MAX_MODPACK_QUERY_LENGTH,
                        "description", "Required literal item ID, tag, recipe type, machine name, or other local search term."),
                "scope", Map.of("type", "string", "enum", List.of("all", "recipes", "kubejs", "quests", "config"),
                        "description", "Limits likely roots. Defaults to all."));
    }

    private static Map<String, Object> modJarInspectionSchema() {
        return Map.of(
                "jar_name", Map.of("type", "string", "minLength", 5, "maxLength", GameDirectoryStore.MAX_MOD_JAR_NAME_LENGTH,
                        "description", "Required direct .jar filename returned by minegpt_list_installed_mods."),
                "query", Map.of("type", "string", "minLength", 1, "maxLength", GameDirectoryStore.MAX_MODPACK_QUERY_LENGTH,
                        "description", "Optional literal term to find in recipe/resource text or printable class strings."));
    }

    private static Map<String, Object> recentLogSchema() {
        return Map.of("max_lines", Map.of("type", "integer", "minimum", 1, "maximum", 1_000,
                "description", "Number of latest log lines to return. Defaults to 200."));
    }

    private static Map<String, Object> radiusSchema() {
        return Map.of("radius", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", 64,
                "description", "Search radius in blocks. Defaults to 32."));
    }

    private static Map<String, Object> blockSchema() {
        return Map.of(
                "x", Map.of("type", "integer"),
                "y", Map.of("type", "integer"),
                "z", Map.of("type", "integer"));
    }

    private static Map<String, Object> chunkSectionSchema() {
        return Map.of(
                "chunk_x", Map.of("type", "integer"),
                "chunk_z", Map.of("type", "integer"),
                "section_y", Map.of("type", "integer", "description", "Vertical chunk section coordinate, where a section spans 16 block Y levels."));
    }

    private static Map<String, Object> replySchema() {
        return Map.of(
                "message_id", Map.of("type", "string"),
                "text", Map.of("type", "string", "maxLength", 4096));
    }

    private static String toMcpPlayerMessage(PlayerMessage message) {
        return ProtocolCodec.toJson(Map.of(
                "type", "player_message",
                "message_id", message.id(),
                "text", message.text(),
                "context", message.context(),
                "created_at_epoch_millis", message.createdAtEpochMillis()));
    }

    private static McpSchema.CallToolResult success(String text) {
        return result(text, false);
    }

    private static McpSchema.CallToolResult failure(String text) {
        return result(text, true);
    }

    private static McpSchema.CallToolResult result(String text, boolean isError) {
        return McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder(text).build()))
                .isError(isError)
                .build();
    }
}
