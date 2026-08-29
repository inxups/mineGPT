package com.inxups.minegpt.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Stores only the bridge pairing token in the Minecraft config directory. */
public final class PairingConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path path;
    private String token;

    public PairingConfig(Path path) {
        this.path = path;
        load();
    }

    public synchronized String token() {
        return token;
    }

    public synchronized boolean isPaired() {
        return token != null && !token.isBlank();
    }

    public synchronized void setToken(String value) throws IOException {
        token = value;
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(new StoredPairing(token)), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void load() {
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            StoredPairing stored = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), StoredPairing.class);
            if (stored != null && stored.token() != null && !stored.token().isBlank()) {
                token = stored.token();
            }
        } catch (IOException | RuntimeException ignored) {
            // A malformed local config must not prevent Minecraft from starting.
        }
    }

    private record StoredPairing(String token) {
    }
}
