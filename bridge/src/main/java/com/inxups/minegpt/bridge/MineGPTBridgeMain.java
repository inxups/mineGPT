package com.inxups.minegpt.bridge;

import io.modelcontextprotocol.server.McpSyncServer;
import com.inxups.minegpt.shared.BridgeEndpoint;
import java.nio.file.Path;

/** Entry point used as a local STDIO MCP server by ChatGPT Desktop. */
public final class MineGPTBridgeMain {
    private static volatile String pairingToken;

    private MineGPTBridgeMain() {
    }

    static String token() {
        return pairingToken;
    }

    public static void main(String[] args) throws Exception {
        Path statePath = Path.of(System.getProperty("user.home"), ".minegpt", "bridge-state.json");
        BridgeStateStore stateStore = new BridgeStateStore(statePath);
        pairingToken = stateStore.token();
        PendingMessageQueue queue = new PendingMessageQueue(stateStore);
        SkillStore skills = new SkillStore();
        LoopbackBridgeServer bridge = new LoopbackBridgeServer(queue, pairingToken, skills);
        bridge.start(BridgeEndpoint.PORT);
        McpSyncServer mcpServer = new MineGPTMcpServer(bridge, skills).start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            mcpServer.closeGracefully();
            bridge.close();
        }, "minegpt-bridge-shutdown"));

        // The MCP host owns this process. It terminates it when the configured server is stopped.
        Thread.currentThread().join();
    }
}
