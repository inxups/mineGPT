package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inxups.minegpt.shared.BridgeEndpoint;
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
