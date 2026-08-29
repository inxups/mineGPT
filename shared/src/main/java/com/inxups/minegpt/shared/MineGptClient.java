package com.inxups.minegpt.shared;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

/** Shared client behaviour; Fabric and NeoForge only supply platform adapters. */
public final class MineGptClient implements AutoCloseable {
    private static final String PREFIX = "@ai";
    private final ClientPlatform platform;
    private final PairingConfig pairingConfig;
    private final LoopbackBridgeClient bridge;

    public MineGptClient(Path configPath, ClientPlatform platform) {
        this.platform = platform;
        pairingConfig = new PairingConfig(configPath);
        bridge = new LoopbackBridgeClient(new LoopbackBridgeClient.Listener() {
            @Override
            public void onReply(String messageId, String text) {
                platform.executeOnClientThread(() -> platform.showLocalMessage("[MineGPT] " + text));
            }

            @Override
            public void onChunkInfoRequest(String requestId, ChunkQuery query) {
                platform.executeOnClientThread(() -> {
                    ChunkInfo info;
                    try {
                        info = platform.readChunkInfo(query);
                    } catch (RuntimeException exception) {
                        info = ChunkInfo.unavailable("Minecraft could not read the chunk: " + exception.getMessage());
                    }
                    bridge.respondToChunkInfo(requestId, info);
                });
            }

            @Override
            public void onGameQueryRequest(String requestId, GameQuery query) {
                platform.executeOnClientThread(() -> {
                    GameQueryResult result;
                    try {
                        result = platform.readGameQuery(query);
                    } catch (RuntimeException exception) {
                        result = GameQueryResult.unavailable("Minecraft could not read game data: " + exception.getMessage());
                    }
                    bridge.respondToGameQuery(requestId, result);
                });
            }

            @Override
            public void onBridgeError(String detail) {
                platform.executeOnClientThread(() -> platform.showLocalMessage("[MineGPT] " + detail));
            }

            @Override
            public void onConnectionChanged(boolean connected) {
                if (connected) {
                    platform.executeOnClientThread(() -> platform.showLocalMessage("[MineGPT] Connected to local bridge."));
                }
            }
        });
        bridge.setToken(pairingConfig.token());
    }

    public void start() {
        bridge.start();
    }

    /**
     * @return false only when the caller should let Minecraft send the original chat to the server.
     */
    public boolean handleChat(String chatMessage) {
        if (chatMessage == null || !(chatMessage.equals(PREFIX) || chatMessage.startsWith(PREFIX + " "))) {
            return false;
        }
        String text = chatMessage.substring(PREFIX.length()).trim();
        if (text.isEmpty()) {
            platform.showLocalMessage("[MineGPT] Usage: @ai <message>");
            return true;
        }
        if (!pairingConfig.isPaired()) {
            platform.showLocalMessage("[MineGPT] Not paired. Ask ChatGPT for minegpt_pairing_code, then run /minegpt pair <token>.");
            return true;
        }
        PlayerMessage message = new PlayerMessage(
                UUID.randomUUID().toString(), text, platform.captureContext(), System.currentTimeMillis());
        if (!bridge.submit(message)) {
            platform.showLocalMessage("[MineGPT] Local bridge queue is full. Wait for ChatGPT to catch up.");
            return true;
        }
        platform.showLocalMessage("[MineGPT] Sent to local bridge.");
        return true;
    }

    public void pair(String token) {
        String normalized = token == null ? "" : token.trim();
        if (normalized.length() < 20 || !normalized.matches("[A-Za-z0-9_-]+")) {
            platform.showLocalMessage("[MineGPT] Invalid pairing token.");
            return;
        }
        try {
            pairingConfig.setToken(normalized);
            bridge.setToken(normalized);
            platform.showLocalMessage("[MineGPT] Pairing token saved. Connecting to local bridge...");
        } catch (IOException exception) {
            platform.showLocalMessage("[MineGPT] Could not save pairing token: " + exception.getMessage());
        }
    }

    public void showStatus() {
        String pairing = pairingConfig.isPaired() ? "paired" : "not paired";
        String connection = bridge.isConnected() ? "connected" : "waiting for bridge";
        platform.showLocalMessage("[MineGPT] " + pairing + "; " + connection + "; "
                + bridge.pendingCount() + " message(s) waiting locally.");
    }

    @Override
    public void close() {
        bridge.close();
    }
}
