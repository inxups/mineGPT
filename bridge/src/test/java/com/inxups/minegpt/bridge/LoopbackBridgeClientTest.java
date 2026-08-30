package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inxups.minegpt.shared.LoopbackBridgeClient;
import com.inxups.minegpt.shared.ChunkQuery;
import com.inxups.minegpt.shared.GameQuery;
import com.inxups.minegpt.shared.PlayerContext;
import com.inxups.minegpt.shared.PlayerMessage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoopbackBridgeClientTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void reconnectsAfterBridgeRestartAndRoutesReplies() throws Exception {
        BridgeStateStore state = new BridgeStateStore(temporaryDirectory.resolve("bridge-state.json"));
        PendingMessageQueue queue = new PendingMessageQueue(state);
        CountDownLatch firstReply = new CountDownLatch(1);
        CountDownLatch secondReply = new CountDownLatch(1);
        AtomicReference<String> lastReply = new AtomicReference<>();

        try (LoopbackBridgeServer firstServer = new LoopbackBridgeServer(queue, state.token())) {
            firstServer.start(0);
            int port = firstServer.port();
            try (LoopbackBridgeClient client = new LoopbackBridgeClient(port, new LoopbackBridgeClient.Listener() {
                @Override
                public void onReply(String messageId, String text) {
                    lastReply.set(text);
                    if ("first reply".equals(text)) {
                        firstReply.countDown();
                    } else if ("second reply".equals(text)) {
                        secondReply.countDown();
                    }
                }

                @Override
                public void onChunkInfoRequest(String requestId, ChunkQuery query) {
                    throw new AssertionError("Unexpected chunk query in this test");
                }

                @Override
                public void onGameQueryRequest(String requestId, GameQuery query) {
                    throw new AssertionError("Unexpected game query in this test");
                }

                @Override
                public void onBridgeError(String detail) {
                    throw new AssertionError(detail);
                }

                @Override
                public void onConnectionChanged(boolean connected) {
                    // Connection state is exercised by the restart below.
                }
            })) {
                client.setToken(state.token());
                client.start();

                PlayerMessage first = message("first");
                client.submit(first);
                assertEquals(first.id(), firstServer.nextMessage(Duration.ofSeconds(5)).orElseThrow().id());
                firstServer.reply(first.id(), "first reply");
                if (!firstReply.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Timed out waiting for the first Minecraft reply");
                }

                firstServer.close();
                try (LoopbackBridgeServer restartedServer = new LoopbackBridgeServer(queue, state.token())) {
                    restartedServer.start(port);
                    PlayerMessage second = message("second");
                    client.submit(second);
                    assertEquals(second.id(), restartedServer.nextMessage(Duration.ofSeconds(8)).orElseThrow().id());
                    restartedServer.reply(second.id(), "second reply");
                    if (!secondReply.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("Timed out waiting for the second Minecraft reply");
                    }
                    assertEquals("second reply", lastReply.get());
                }
            }
        }
    }

    @Test
    void notifiesTheBridgeOnlyWhenTheMinecraftClientExits() throws Exception {
        BridgeStateStore state = new BridgeStateStore(temporaryDirectory.resolve("bridge-state.json"));
        PendingMessageQueue queue = new PendingMessageQueue(state);
        CountDownLatch connected = new CountDownLatch(1);
        CountDownLatch minecraftExited = new CountDownLatch(1);
        try (LoopbackBridgeServer server = new LoopbackBridgeServer(
                queue, state.token(), new SkillStore(), minecraftExited::countDown)) {
            server.start(0);
            try (LoopbackBridgeClient client = new LoopbackBridgeClient(server.port(), listener(connected))) {
                client.setToken(state.token());
                client.start();
                assertTrue(connected.await(2, TimeUnit.SECONDS));

                client.closeForClientExit();
                assertTrue(minecraftExited.await(2, TimeUnit.SECONDS));
            }
        }
    }

    private static LoopbackBridgeClient.Listener listener(CountDownLatch connected) {
        return new LoopbackBridgeClient.Listener() {
            @Override
            public void onReply(String messageId, String text) {
                // Not used by this lifecycle test.
            }

            @Override
            public void onChunkInfoRequest(String requestId, ChunkQuery query) {
                throw new AssertionError("Unexpected chunk query in this test");
            }

            @Override
            public void onGameQueryRequest(String requestId, GameQuery query) {
                throw new AssertionError("Unexpected game query in this test");
            }

            @Override
            public void onBridgeError(String detail) {
                throw new AssertionError(detail);
            }

            @Override
            public void onConnectionChanged(boolean isConnected) {
                if (isConnected) {
                    connected.countDown();
                }
            }
        };
    }

    private static PlayerMessage message(String suffix) {
        return new PlayerMessage("message-" + suffix, "message " + suffix,
                new PlayerContext("Alex", "singleplayer", "minecraft:overworld", 1, 64, 1, 20, 20, "survival"),
                System.currentTimeMillis());
    }
}
