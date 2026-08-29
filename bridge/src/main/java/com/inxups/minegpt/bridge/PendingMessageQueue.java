package com.inxups.minegpt.bridge;

import com.inxups.minegpt.shared.PlayerMessage;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

/** FIFO queue with durable messages, expiration, and short leases for MCP long polling. */
final class PendingMessageQueue {
    static final int MAX_MESSAGES = 200;
    private static final Duration RETENTION = Duration.ofHours(24);
    private static final Duration LEASE = Duration.ofMinutes(1);

    enum EnqueueResult {
        ENQUEUED,
        DUPLICATE,
        FULL
    }

    private final BridgeStateStore stateStore;
    private final Clock clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();

    PendingMessageQueue(BridgeStateStore stateStore) {
        this(stateStore, Clock.systemUTC());
    }

    PendingMessageQueue(BridgeStateStore stateStore, Clock clock) {
        this.stateStore = stateStore;
        this.clock = clock;
        for (PlayerMessage message : stateStore.messages()) {
            entries.put(message.id(), new Entry(message, 0));
        }
        cleanExpired();
    }

    synchronized EnqueueResult enqueue(PlayerMessage message) {
        cleanExpired();
        if (entries.containsKey(message.id())) {
            return EnqueueResult.DUPLICATE;
        }
        if (entries.size() >= MAX_MESSAGES) {
            return EnqueueResult.FULL;
        }
        entries.put(message.id(), new Entry(message, 0));
        persist();
        notifyAll();
        return EnqueueResult.ENQUEUED;
    }

    synchronized Optional<PlayerMessage> next(Duration waitDuration) throws InterruptedException {
        long waitMillis = Math.max(0, waitDuration.toMillis());
        long deadline = clock.millis() + waitMillis;
        while (true) {
            cleanExpired();
            long now = clock.millis();
            for (Entry entry : entries.values()) {
                if (entry.leaseUntilEpochMillis <= now) {
                    entry.leaseUntilEpochMillis = now + LEASE.toMillis();
                    return Optional.of(entry.message);
                }
            }
            long remaining = deadline - clock.millis();
            if (remaining <= 0) {
                return Optional.empty();
            }
            wait(remaining);
        }
    }

    synchronized boolean remove(String messageId) {
        Entry removed = entries.remove(messageId);
        if (removed == null) {
            return false;
        }
        persist();
        return true;
    }

    synchronized boolean release(String messageId) {
        Entry entry = entries.get(messageId);
        if (entry == null) {
            return false;
        }
        entry.leaseUntilEpochMillis = 0;
        notifyAll();
        return true;
    }

    synchronized boolean contains(String messageId) {
        cleanExpired();
        return entries.containsKey(messageId);
    }

    synchronized int size() {
        cleanExpired();
        return entries.size();
    }

    private void cleanExpired() {
        long oldestAllowed = clock.millis() - RETENTION.toMillis();
        boolean changed = entries.values().removeIf(entry -> entry.message.createdAtEpochMillis() < oldestAllowed);
        if (changed) {
            persist();
        }
    }

    private void persist() {
        List<PlayerMessage> messages = new ArrayList<>();
        for (Entry entry : entries.values()) {
            messages.add(entry.message);
        }
        stateStore.save(messages);
    }

    private static final class Entry {
        private final PlayerMessage message;
        private long leaseUntilEpochMillis;

        private Entry(PlayerMessage message, long leaseUntilEpochMillis) {
            this.message = message;
            this.leaseUntilEpochMillis = leaseUntilEpochMillis;
        }
    }
}
