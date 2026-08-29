package com.inxups.minegpt.shared;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reconnecting client for the authenticated loopback bridge. Pending messages stay in
 * memory until the bridge acknowledges them, so a temporary bridge restart does not
 * silently lose a player message.
 */
public final class LoopbackBridgeClient implements AutoCloseable {
    public static final int PORT = 37832;
    private static final int MAX_PENDING_MESSAGES = 200;

    public interface Listener {
        void onReply(String messageId, String text);

        void onBridgeError(String detail);

        void onConnectionChanged(boolean connected);
    }

    private final Listener listener;
    private final int port;
    private final LinkedBlockingDeque<ProtocolMessage> pending = new LinkedBlockingDeque<>(MAX_PENDING_MESSAGES);
    private final AtomicReference<String> token = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private volatile Socket socket;
    private Thread worker;

    public LoopbackBridgeClient(Listener listener) {
        this(PORT, listener);
    }

    /** The alternate port constructor is intended for local integration tests. */
    public LoopbackBridgeClient(int port, Listener listener) {
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("Invalid MineGPT bridge port");
        }
        this.port = port;
        this.listener = listener;
    }

    public void setToken(String pairingToken) {
        token.set(pairingToken);
        closeSocket();
    }

    public boolean submit(PlayerMessage message) {
        return pending.offerLast(ProtocolMessage.playerMessage(message));
    }

    public int pendingCount() {
        return pending.size();
    }

    public boolean isConnected() {
        return connected.get();
    }

    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        worker = Thread.ofVirtual().name("minegpt-bridge-client").start(this::runLoop);
    }

    private void runLoop() {
        long retryDelayMillis = 1_000;
        while (running.get()) {
            String currentToken = token.get();
            if (currentToken == null || currentToken.isBlank()) {
                sleep(250);
                continue;
            }
            try (Socket localSocket = new Socket()) {
                socket = localSocket;
                localSocket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 2_000);
                localSocket.setSoTimeout(250);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(localSocket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(localSocket.getOutputStream(), StandardCharsets.UTF_8))) {
                    write(writer, ProtocolMessage.hello(currentToken));
                    ProtocolMessage hello = readRequired(reader);
                    if (!"hello_accepted".equals(hello.type())) {
                        listener.onBridgeError(hello.detail() == null ? "Bridge rejected the pairing token." : hello.detail());
                        sleep(2_000);
                        continue;
                    }
                    setConnected(true);
                    retryDelayMillis = 1_000;
                    sendUntilDisconnected(reader, writer);
                }
            } catch (IOException | IllegalArgumentException ignored) {
                // The bridge may be unavailable while ChatGPT Desktop is not running.
            } finally {
                socket = null;
                setConnected(false);
            }
            sleep(retryDelayMillis);
            retryDelayMillis = Math.min(retryDelayMillis * 2, 30_000);
        }
    }

    private void sendUntilDisconnected(BufferedReader reader, BufferedWriter writer) throws IOException {
        ProtocolMessage awaitingAcknowledgement = null;
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            if (awaitingAcknowledgement == null) {
                awaitingAcknowledgement = pending.peekFirst();
                if (awaitingAcknowledgement != null) {
                    write(writer, awaitingAcknowledgement);
                }
            }
            try {
                String line = reader.readLine();
                if (line == null) {
                    throw new IOException("Bridge connection closed");
                }
                ProtocolMessage incoming = ProtocolCodec.decode(line);
                if ("accepted".equals(incoming.type()) && awaitingAcknowledgement != null
                        && awaitingAcknowledgement.messageId().equals(incoming.messageId())) {
                    pending.pollFirst();
                    awaitingAcknowledgement = null;
                } else if ("reply".equals(incoming.type())) {
                    listener.onReply(incoming.messageId(), incoming.text());
                } else if ("error".equals(incoming.type())) {
                    listener.onBridgeError(incoming.detail() == null ? "Bridge rejected a message." : incoming.detail());
                    if (awaitingAcknowledgement != null) {
                        pending.pollFirst();
                        awaitingAcknowledgement = null;
                    }
                }
            } catch (java.net.SocketTimeoutException timeout) {
                if (awaitingAcknowledgement == null) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(25);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }

    private static ProtocolMessage readRequired(BufferedReader reader) throws IOException {
        String line = reader.readLine();
        if (line == null) {
            throw new IOException("Bridge closed during handshake");
        }
        return ProtocolCodec.decode(line);
    }

    private static void write(BufferedWriter writer, ProtocolMessage message) throws IOException {
        writer.write(ProtocolCodec.encode(message));
        writer.newLine();
        writer.flush();
    }

    private void setConnected(boolean value) {
        if (connected.getAndSet(value) != value) {
            listener.onConnectionChanged(value);
        }
    }

    private static void sleep(long durationMillis) {
        try {
            Thread.sleep(durationMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void closeSocket() {
        Socket currentSocket = socket;
        if (currentSocket != null) {
            try {
                currentSocket.close();
            } catch (IOException ignored) {
                // Closing is only used to force a reconnect.
            }
        }
    }

    @Override
    public void close() {
        running.set(false);
        closeSocket();
        if (worker != null) {
            worker.interrupt();
        }
    }
}
