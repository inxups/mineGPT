package com.inxups.minegpt.bridge;

import com.inxups.minegpt.shared.PlayerMessage;
import com.inxups.minegpt.shared.BridgeEndpoint;
import com.inxups.minegpt.shared.ProtocolCodec;
import com.inxups.minegpt.shared.ProtocolMessage;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated TCP bridge that is reachable only through the loopback interface. */
final class LoopbackBridgeServer implements AutoCloseable {
    private final PendingMessageQueue queue;
    private final String token;
    private final ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicReference<ClientConnection> activeConnection = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private ServerSocket serverSocket;
    private Thread acceptThread;

    LoopbackBridgeServer(PendingMessageQueue queue, String token) {
        this.queue = queue;
        this.token = token;
    }

    synchronized void start(int requestedPort) throws IOException {
        if (running.get()) {
            return;
        }
        serverSocket = new ServerSocket(requestedPort, 16, BridgeEndpoint.address());
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
            ClientConnection connection = new ClientConnection(socket, writer);
            ClientConnection previous = activeConnection.getAndSet(connection);
            if (previous != null) {
                previous.close();
            }
            connection.send(ProtocolMessage.helloAccepted());

            String line;
            while ((line = reader.readLine()) != null) {
                ProtocolMessage message = ProtocolCodec.decode(line);
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
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // The server is already stopping.
        }
        clients.close();
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
