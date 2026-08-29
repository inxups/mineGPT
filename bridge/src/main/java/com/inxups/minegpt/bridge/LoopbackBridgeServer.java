package com.inxups.minegpt.bridge;

import com.inxups.minegpt.shared.PlayerMessage;
import com.inxups.minegpt.shared.BridgeEndpoint;
import com.inxups.minegpt.shared.ChunkInfo;
import com.inxups.minegpt.shared.ChunkQuery;
import com.inxups.minegpt.shared.GameQuery;
import com.inxups.minegpt.shared.GameQueryResult;
import com.inxups.minegpt.shared.ProtocolCodec;
import com.inxups.minegpt.shared.ProtocolMessage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated TCP bridge that is reachable only through the loopback interface. */
final class LoopbackBridgeServer implements AutoCloseable {
    private final PendingMessageQueue queue;
    private final String token;
    private final SkillStore skills;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<ClientConnection> activeConnection = new AtomicReference<>();
    private final ConcurrentHashMap<String, CompletableFuture<ChunkInfo>> pendingChunkRequests = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<GameQueryResult>> pendingGameQueries = new ConcurrentHashMap<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;
    private Thread acceptThread;

    LoopbackBridgeServer(PendingMessageQueue queue, String token) {
        this(queue, token, new SkillStore());
    }

    LoopbackBridgeServer(PendingMessageQueue queue, String token, SkillStore skills) {
        this.queue = queue;
        this.token = token;
        this.skills = skills;
    }

    synchronized void start(int requestedPort) throws IOException {
        if (running.get()) {
            return;
        }
        ServerSocket socket = new ServerSocket();
        socket.setReuseAddress(true);
        socket.bind(new InetSocketAddress(BridgeEndpoint.address(), requestedPort), 16);
        serverSocket = socket;
        running.set(true);
        acceptThread = Thread.ofVirtual().name("minegpt-bridge-accept").start(this::acceptLoop);
    }

    int port() {
        ServerSocket current = serverSocket;
        return current == null ? -1 : current.getLocalPort();
    }

    BridgeStatus status() {
        return new BridgeStatus(port(), activeConnection.get() != null, queue.size());
    }

    Optional<PlayerMessage> nextMessage(Duration wait) throws InterruptedException {
        return queue.next(wait);
    }

    boolean reply(String messageId, String text) {
        if (!queue.contains(messageId)) {
            return false;
        }
        ClientConnection connection = activeConnection.get();
        if (connection == null) {
            queue.release(messageId);
            return false;
        }
        try {
            connection.send(ProtocolMessage.reply(messageId, text));
            return queue.remove(messageId);
        } catch (IOException exception) {
            activeConnection.compareAndSet(connection, null);
            connection.close();
            queue.release(messageId);
            return false;
        }
    }

    /**
     * Requests a snapshot from the Minecraft client without loading a new chunk. The socket
     * protocol is asynchronous, while MCP tool invocations are synchronous, so this method
     * bounds the wait rather than allowing a disconnected game to hold a tool call indefinitely.
     */
    ChunkInfo readChunkInfo(ChunkQuery query) {
        if (query == null || !query.isValid()) {
            return ChunkInfo.unavailable("Provide both chunk_x and chunk_z, or neither to use the player chunk.");
        }
        ClientConnection connection = activeConnection.get();
        if (connection == null) {
            return ChunkInfo.unavailable("Minecraft client is not connected to the local bridge.");
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<ChunkInfo> response = new CompletableFuture<>();
        pendingChunkRequests.put(requestId, response);
        try {
            connection.send(ProtocolMessage.chunkInfoRequest(requestId, query));
            return response.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ChunkInfo.unavailable("Chunk query was interrupted.");
        } catch (Exception exception) {
            return ChunkInfo.unavailable("Minecraft did not answer the chunk query within 5 seconds.");
        } finally {
            pendingChunkRequests.remove(requestId);
        }
    }

    GameQueryResult readGameQuery(GameQuery query) {
        if (query == null || query.kind() == null || query.kind().isBlank()) {
            return GameQueryResult.unavailable("Invalid game-data query.");
        }
        ClientConnection connection = activeConnection.get();
        if (connection == null) {
            return GameQueryResult.unavailable("Minecraft client is not connected to the local bridge.");
        }
        String requestId = UUID.randomUUID().toString();
        CompletableFuture<GameQueryResult> response = new CompletableFuture<>();
        pendingGameQueries.put(requestId, response);
        try {
            connection.send(ProtocolMessage.gameQueryRequest(requestId, query));
            return response.get(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return GameQueryResult.unavailable("Game-data query was interrupted.");
        } catch (Exception exception) {
            return GameQueryResult.unavailable("Minecraft did not answer the game-data query within 5 seconds.");
        } finally {
            pendingGameQueries.remove(requestId);
        }
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = serverSocket.accept();
                clients.submit(() -> handle(socket));
            } catch (SocketException closed) {
                if (running.get()) {
                    System.err.println("MineGPT bridge socket error: " + closed.getMessage());
                }
            } catch (IOException exception) {
                System.err.println("MineGPT bridge accept error: " + exception.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            String helloLine = reader.readLine();
            ProtocolMessage hello = ProtocolCodec.decode(helloLine);
            if (!"hello".equals(hello.type()) || !token.equals(hello.token())) {
                write(writer, ProtocolMessage.error("Invalid MineGPT pairing token."));
                return;
            }
            if (hello.gameDirectory() != null) {
                try {
                    skills.setMinecraftDirectory(java.nio.file.Path.of(hello.gameDirectory()));
                } catch (Exception exception) {
                    write(writer, ProtocolMessage.error("Invalid Minecraft game directory."));
                    return;
                }
            }
            ClientConnection connection = new ClientConnection(socket, writer);
            ClientConnection previous = activeConnection.getAndSet(connection);
            if (previous != null) {
                previous.close();
            }
            connection.send(ProtocolMessage.helloAccepted());

            String line;
            while ((line = reader.readLine()) != null) {
                ProtocolMessage message = ProtocolCodec.decode(line);
                if ("chunk_info_response".equals(message.type())) {
                    completeChunkRequest(message);
                    continue;
                }
                if ("game_query_response".equals(message.type())) {
                    completeGameQuery(message);
                    continue;
                }
                if (!"player_message".equals(message.type()) || message.message() == null
                        || !message.message().id().equals(message.messageId())) {
                    connection.send(ProtocolMessage.error("Invalid Minecraft message."));
                    continue;
                }
                PlayerMessage playerMessage = message.message();
                if (playerMessage.context() == null || playerMessage.text() == null || playerMessage.text().isBlank()
                        || playerMessage.text().length() > 4_096) {
                    connection.send(ProtocolMessage.error("Minecraft message must be between 1 and 4096 characters."));
                    continue;
                }
                PendingMessageQueue.EnqueueResult result = queue.enqueue(playerMessage);
                if (result == PendingMessageQueue.EnqueueResult.FULL) {
                    connection.send(ProtocolMessage.error("MineGPT bridge queue is full."));
                } else {
                    connection.send(ProtocolMessage.accepted(playerMessage.id()));
                }
            }
        } catch (IOException | IllegalArgumentException ignored) {
            // A local Mod reconnects automatically after malformed input or a disconnect.
        } finally {
            activeConnection.getAndUpdate(connection -> connection != null && connection.socket == socket ? null : connection);
        }
    }

    private void completeChunkRequest(ProtocolMessage message) {
        if (message.requestId() == null || message.chunkInfo() == null) {
            return;
        }
        CompletableFuture<ChunkInfo> request = pendingChunkRequests.remove(message.requestId());
        if (request != null) {
            request.complete(message.chunkInfo());
        }
    }

    private void completeGameQuery(ProtocolMessage message) {
        if (message.requestId() == null || message.gameQueryResult() == null) {
            return;
        }
        CompletableFuture<GameQueryResult> request = pendingGameQueries.remove(message.requestId());
        if (request != null) {
            request.complete(message.gameQueryResult());
        }
    }

    private static void write(BufferedWriter writer, ProtocolMessage message) throws IOException {
        writer.write(ProtocolCodec.encode(message));
        writer.newLine();
        writer.flush();
    }

    @Override
    public synchronized void close() {
        if (!running.getAndSet(false)) {
            return;
        }
        ClientConnection connection = activeConnection.getAndSet(null);
        if (connection != null) {
            connection.close();
        }
        pendingChunkRequests.forEach((requestId, request) ->
                request.complete(ChunkInfo.unavailable("MineGPT bridge stopped.")));
        pendingChunkRequests.clear();
        pendingGameQueries.forEach((requestId, request) ->
                request.complete(GameQueryResult.unavailable("MineGPT bridge stopped.")));
        pendingGameQueries.clear();
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // The server is already stopping.
        }
        clients.close();
        skills.close();
        if (acceptThread != null) {
            acceptThread.interrupt();
        }
    }

    private static final class ClientConnection {
        private final Socket socket;
        private final BufferedWriter writer;

        private ClientConnection(Socket socket, BufferedWriter writer) {
            this.socket = socket;
            this.writer = writer;
        }

        private synchronized void send(ProtocolMessage message) throws IOException {
            write(writer, message);
        }

        private void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Nothing further to do for a closed local connection.
            }
        }
    }
}
