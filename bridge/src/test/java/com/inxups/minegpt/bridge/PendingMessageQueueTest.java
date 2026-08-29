package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.inxups.minegpt.shared.PlayerContext;
import com.inxups.minegpt.shared.PlayerMessage;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PendingMessageQueueTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void retainsFifoOrderAndPersistsAcrossQueueInstances() throws Exception {
        BridgeStateStore state = new BridgeStateStore(temporaryDirectory.resolve("bridge-state.json"));
        PendingMessageQueue queue = new PendingMessageQueue(state);
        PlayerMessage first = message("first", System.currentTimeMillis());
        PlayerMessage second = message("second", System.currentTimeMillis());

        assertEquals(PendingMessageQueue.EnqueueResult.ENQUEUED, queue.enqueue(first));
        assertEquals(PendingMessageQueue.EnqueueResult.ENQUEUED, queue.enqueue(second));
        assertEquals(PendingMessageQueue.EnqueueResult.DUPLICATE, queue.enqueue(first));
        assertEquals(first.id(), queue.next(java.time.Duration.ZERO).orElseThrow().id());
        assertTrue(queue.remove(first.id()));

        PendingMessageQueue restored = new PendingMessageQueue(new BridgeStateStore(temporaryDirectory.resolve("bridge-state.json")));
        assertEquals(second.id(), restored.next(java.time.Duration.ZERO).orElseThrow().id());
        assertFalse(restored.remove(first.id()));
    }

    @Test
    void dropsMessagesOlderThanTwentyFourHours() {
        Instant now = Instant.parse("2026-08-29T00:00:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        BridgeStateStore state = new BridgeStateStore(temporaryDirectory.resolve("expired-state.json"));
        state.save(List.of(message("expired", now.minusSeconds(24 * 60 * 60 + 1).toEpochMilli())));

        PendingMessageQueue queue = new PendingMessageQueue(state, clock);

        assertEquals(0, queue.size());
    }

    private static PlayerMessage message(String suffix, long createdAt) {
        return new PlayerMessage("message-" + suffix, "text-" + suffix,
                new PlayerContext("Alex", "singleplayer", "minecraft:overworld", 1, 64, 1, 20, 20, "survival"),
                createdAt);
    }
}
