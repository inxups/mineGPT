package com.inxups.minegpt.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MineGPTClientTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void interceptsGptPrefixButNotThePreviousAiPrefix() {
        TestPlatform platform = new TestPlatform(temporaryDirectory);
        try (MineGPTClient client = new MineGPTClient(temporaryDirectory.resolve("minegpt.json"), platform)) {
            assertTrue(client.handleChat("@gpt"));
            assertEquals("[MineGPT] Usage: @gpt <message>", platform.messages.getLast());

            assertTrue(client.handleChat("@gpt Where am I?"));
            assertEquals("[MineGPT] Not paired. Ask ChatGPT for minegpt_pairing_code, then run /minegpt pair <token>.",
                    platform.messages.getLast());

            assertFalse(client.handleChat("@ai Where am I?"));
        }
    }

    private static final class TestPlatform implements ClientPlatform {
        private final Path gameDirectory;
        private final List<String> messages = new ArrayList<>();

        private TestPlatform(Path gameDirectory) {
            this.gameDirectory = gameDirectory;
        }

        @Override
        public PlayerContext captureContext() {
            return null;
        }

        @Override
        public ChunkInfo readChunkInfo(ChunkQuery query) {
            return null;
        }

        @Override
        public GameQueryResult readGameQuery(GameQuery query) {
            return null;
        }

        @Override
        public Path gameDirectory() {
            return gameDirectory;
        }

        @Override
        public void executeOnClientThread(Runnable action) {
            action.run();
        }

        @Override
        public void showLocalMessage(String message) {
            messages.add(message);
        }
    }
}
