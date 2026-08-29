package com.inxups.minegpt.bridge;

import com.inxups.minegpt.shared.PlayerMessage;
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

/** Registers the four small MCP tools used by the ChatGPT Desktop listening conversation. */
final class MineGptMcpServer {
    private static final String INSTRUCTIONS = "MineGPT bridges a live local Minecraft chat. When asked to listen, call minegpt_next_message with wait_seconds 45. For every player_message, answer the player, call minegpt_reply with its exact message_id, then immediately listen again. Do not claim delivery unless minegpt_reply succeeds.";

    private final LoopbackBridgeServer bridge;

    MineGptMcpServer(LoopbackBridgeServer bridge) {
        this.bridge = bridge;
    }

    McpSyncServer start() {
        StdioServerTransportProvider transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());
        return McpServer.sync(transport)
                .serverInfo("minegpt", "0.1.0")
                .instructions(INSTRUCTIONS)
                .toolCall(tool("minegpt_status", "Return MineGPT bridge connection and queue status.", Map.of()),
                        (exchange, request) -> success(ProtocolCodec.toJson(bridge.status())))
                .toolCall(tool("minegpt_pairing_code", "Return the one-time pairing token for the Minecraft client.", Map.of()),
                        (exchange, request) -> success(ProtocolCodec.toJson(Map.of(
                                "host", "127.0.0.1",
                                "port", bridge.port(),
                                "token", bridgeToken()))))
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
