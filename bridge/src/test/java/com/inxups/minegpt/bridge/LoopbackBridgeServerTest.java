package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inxups.minegpt.shared.BridgeEndpoint;
import com.inxups.minegpt.shared.ChunkInfo;
import com.inxups.minegpt.shared.ChunkQuery;
import com.inxups.minegpt.shared.GameQuery;
import com.inxups.minegpt.shared.GameQueryResult;
import com.inxups.minegpt.shared.PlayerContext;
import com.inxups.minegpt.shared.PlayerMessage;
import com.inxups.minegpt.shared.ProtocolCodec;
import com.inxups.minegpt.shared.ProtocolMessage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoopbackBridgeServerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsBadTokensAndRoutesAMessageAndReply() throws Exception {
        BridgeStateStore state = new BridgeStateStore(temporaryDirectory.resolve("bridge-state.json"));
        PendingMessageQueue queue = new PendingMessageQueue(state);
        try (LoopbackBridgeServer server = new LoopbackBridgeServer(queue, state.token())) {
            server.start(0);
            try (Socket rejected = new Socket(BridgeEndpoint.address(), server.port());
                 BufferedReader reader = reader(rejected);
                 BufferedWriter writer = writer(rejected)) {
                send(writer, ProtocolMessage.hello("incorrect-token"));
                assertEquals("error", receive(reader).type());
            }

            try (Socket accepted = new Socket(BridgeEndpoint.address(), server.port());
                 BufferedReader reader = reader(accepted);
                 BufferedWriter writer = writer(accepted)) {
                send(writer, ProtocolMessage.hello(state.token()));
                assertEquals("hello_accepted", receive(reader).type());

                PlayerMessage message = new PlayerMessage("message-1", "Where is iron?",
                        new PlayerContext("Alex", "singleplayer", "minecraft:overworld", 1, 64, 1, 20, 20, "survival"),
                        System.currentTimeMillis());
                send(writer, ProtocolMessage.playerMessage(message));
                assertEquals("accepted", receive(reader).type());
                assertEquals(message.id(), server.nextMessage(Duration.ZERO).orElseThrow().id());

                assertTrue(server.reply(message.id(), "Look below y=16."));
                ProtocolMessage reply = receive(reader);
                assertEquals("reply", reply.type());
                assertEquals(message.id(), reply.messageId());
                assertEquals("Look below y=16.", reply.text());
            }
        }
    }

    @Test
    void requestsAChunkSnapshotFromTheConnectedMinecraftClient() throws Exception {
        BridgeStateStore state = new BridgeStateStore(temporaryDirectory.resolve("bridge-state.json"));
        PendingMessageQueue queue = new PendingMessageQueue(state);
        try (LoopbackBridgeServer server = new LoopbackBridgeServer(queue, state.token())) {
            server.start(0);
            try (Socket client = new Socket(BridgeEndpoint.address(), server.port());
                 BufferedReader reader = reader(client);
                 BufferedWriter writer = writer(client)) {
                send(writer, ProtocolMessage.hello(state.token()));
                assertEquals("hello_accepted", receive(reader).type());

                CompletableFuture<ChunkInfo> result = CompletableFuture.supplyAsync(
                        () -> server.readChunkInfo(new ChunkQuery(4, -2)));
                ProtocolMessage request = receive(reader);
                assertEquals("chunk_info_request", request.type());
                assertEquals(new ChunkQuery(4, -2), request.chunkQuery());

                ChunkInfo expected = new ChunkInfo(true, null, "minecraft:overworld", 4, -2,
                        1200L, -64, 320, new int[256], new String[256]);
                send(writer, ProtocolMessage.chunkInfoResponse(request.requestId(), expected));
                ChunkInfo received = result.get(2, TimeUnit.SECONDS);
                assertTrue(received.loaded());
                assertEquals("minecraft:overworld", received.dimension());
                assertEquals(4, received.chunkX());
                assertEquals(-2, received.chunkZ());
                assertEquals(256, received.surfaceHeights().length);
                assertEquals(256, received.surfaceBlocks().length);
            }
        }
    }

    @Test
    void requestsBoundedGameDataFromTheConnectedMinecraftClient() throws Exception {
        BridgeStateStore state = new BridgeStateStore(temporaryDirectory.resolve("bridge-state.json"));
        PendingMessageQueue queue = new PendingMessageQueue(state);
        try (LoopbackBridgeServer server = new LoopbackBridgeServer(queue, state.token())) {
            server.start(0);
            try (Socket client = new Socket(BridgeEndpoint.address(), server.port());
                 BufferedReader reader = reader(client);
                 BufferedWriter writer = writer(client)) {
                send(writer, ProtocolMessage.hello(state.token()));
                assertEquals("hello_accepted", receive(reader).type());

                CompletableFuture<GameQueryResult> result = CompletableFuture.supplyAsync(
                        () -> server.readGameQuery(GameQuery.playerState()));
                ProtocolMessage request = receive(reader);
                assertEquals("game_query_request", request.type());
                assertEquals("player_state", request.gameQuery().kind());

                send(writer, ProtocolMessage.gameQueryResponse(request.requestId(),
                        GameQueryResult.available(java.util.Map.of("health", 20, "hunger", 20))));
                GameQueryResult received = result.get(2, TimeUnit.SECONDS);
                assertTrue(received.available());
                assertTrue(received.dataJson().contains("\"health\":20"));
            }
        }
    }

    @Test
    void usesTheGameDirectoryReportedByTheMinecraftClient() throws Exception {
        BridgeStateStore state = new BridgeStateStore(temporaryDirectory.resolve("bridge-state.json"));
        PendingMessageQueue queue = new PendingMessageQueue(state);
        SkillStore skills = new SkillStore(temporaryDirectory.resolve("fallback-instance"));
        try (LoopbackBridgeServer server = new LoopbackBridgeServer(queue, state.token(), skills)) {
            server.start(0);
            try (Socket client = new Socket(BridgeEndpoint.address(), server.port());
                 BufferedReader reader = reader(client);
                 BufferedWriter writer = writer(client)) {
                Path instanceDirectory = temporaryDirectory.resolve("actual-run").toAbsolutePath();
                send(writer, ProtocolMessage.hello(state.token(), instanceDirectory.toString()));
                assertEquals("hello_accepted", receive(reader).type());
                assertTrue(skills.directory().startsWith(instanceDirectory));
                assertTrue(java.nio.file.Files.isRegularFile(
                        instanceDirectory.resolve("minegpt/skills/minegpt-guide.md")));
            }
        }
    }

    private static BufferedReader reader(Socket socket) throws Exception {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
    }

    private static BufferedWriter writer(Socket socket) throws Exception {
        return new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
    }

    private static void send(BufferedWriter writer, ProtocolMessage message) throws Exception {
        writer.write(ProtocolCodec.encode(message));
        writer.newLine();
        writer.flush();
    }

    private static ProtocolMessage receive(BufferedReader reader) throws Exception {
        return ProtocolCodec.decode(reader.readLine());
    }
}
