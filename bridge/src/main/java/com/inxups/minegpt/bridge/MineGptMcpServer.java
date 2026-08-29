package com.inxups.minegpt.bridge;

import com.inxups.minegpt.shared.PlayerMessage;
import com.inxups.minegpt.shared.ChunkInfo;
import com.inxups.minegpt.shared.ChunkQuery;
import com.inxups.minegpt.shared.GameQuery;
import com.inxups.minegpt.shared.GameQueryResult;
import com.inxups.minegpt.shared.ProtocolCodec;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Registers the MCP tools used by the ChatGPT Desktop listening conversation. */
final class MineGptMcpServer {
    private final LoopbackBridgeServer bridge;
    private final SkillStore skills;

    MineGptMcpServer(LoopbackBridgeServer bridge) {
        this(bridge, new SkillStore());
    }

    MineGptMcpServer(LoopbackBridgeServer bridge, SkillStore skills) {
        this.bridge = bridge;
        this.skills = skills;
        try {
            skills.initialize();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not initialize MineGPT skill directory.", exception);
        }
    }

    McpSyncServer start() {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        return McpServer.sync(transport)
                .serverInfo("minegpt", "0.1.0")
                .instructions(instructions())
                .toolCall(tool("minegpt_status", "Return MineGPT bridge connection and queue status.", Map.of()),
                        (exchange, request) -> success(ProtocolCodec.toJson(bridge.status())))
                .toolCall(tool("minegpt_pairing_code", "Return the one-time pairing token for the Minecraft client.", Map.of()),
                        (exchange, request) -> success(ProtocolCodec.toJson(Map.of(
                                "host", "127.0.0.1",
                                "port", bridge.port(),
                                "token", bridgeToken()))))
                .toolCall(tool("minegpt_list_skills", "List user-editable MineGPT Markdown skills from the local Minecraft minegpt/skills directory.", Map.of()),
                        (exchange, request) -> listSkills())
                .toolCall(tool("minegpt_get_skill", "Read one Markdown skill from the local Minecraft minegpt/skills directory. Call minegpt_list_skills first to discover valid names.", skillSchema()),
                        (exchange, request) -> getSkill(request.arguments()))
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
        return MineGptBridgeMain.token();
    }

    private String instructions() {
        return "MineGPT bridges a local Minecraft client. User-editable Markdown skills live in "
                + skills.directory() + ". At the start of a MineGPT task, call minegpt_list_skills and load the skill relevant to the player's request with minegpt_get_skill. "
                + "A default minegpt-guide.md is created if missing. Game-data tools are read-only snapshots of client-visible data: they cannot load chunks from a server or modify the game.";
    }

    private McpSchema.CallToolResult listSkills() {
        try {
            return success(ProtocolCodec.toJson(Map.of(
                    "skills_directory", skills.directory().toString(),
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

    private McpSchema.CallToolResult nextMessage(Map<String, Object> arguments) {
        int waitSeconds = numberArgument(arguments, "wait_seconds", 45);
        if (waitSeconds < 1 || waitSeconds > 45) {
            return failure("wait_seconds must be between 1 and 45.");
        }
        try {
            Optional<PlayerMessage> message = bridge.nextMessage(Duration.ofSeconds(waitSeconds));
            return success(message.<String>map(MineGptMcpServer::toMcpPlayerMessage).orElse("{\"status\":\"idle\"}"));
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
                "description", "Exact Markdown filename returned by minegpt_list_skills; omit to load minegpt-guide.md."));
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
