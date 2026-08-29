package com.inxups.minegpt.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.inxups.minegpt.shared.PlayerMessage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;

/** Durable, local-only state for the pairing token and messages awaiting an AI reply. */
final class BridgeStateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    private final Path statePath;
    private final String token;
    private List<PlayerMessage> messages;

    BridgeStateStore(Path statePath) {
        this.statePath = statePath;
        StoredState stored = read();
        token = stored.token() == null || stored.token().isBlank() ? generateToken() : stored.token();
        messages = stored.messages() == null ? new ArrayList<>() : new ArrayList<>(stored.messages());
        save(messages);
    }

    synchronized String token() {
        return token;
    }

    synchronized List<PlayerMessage> messages() {
        return List.copyOf(messages);
    }

    synchronized void save(List<PlayerMessage> nextMessages) {
        messages = new ArrayList<>(nextMessages);
        try {
            Files.createDirectories(statePath.getParent());
            Path temporary = statePath.resolveSibling(statePath.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(new StoredState(token, messages)), StandardCharsets.UTF_8);
            setOwnerOnlyPermissions(temporary);
            try {
                Files.move(temporary, statePath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailure) {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING);
            }
            setOwnerOnlyPermissions(statePath);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot save MineGPT bridge state", exception);
        }
    }

    private StoredState read() {
        if (!Files.isRegularFile(statePath)) {
            return new StoredState(null, List.of());
        }
        try {
            StoredState stored = GSON.fromJson(Files.readString(statePath, StandardCharsets.UTF_8), StoredState.class);
            return stored == null ? new StoredState(null, List.of()) : stored;
        } catch (IOException | RuntimeException ignored) {
            return new StoredState(null, List.of());
        }
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void setOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows ACLs and file systems without POSIX permissions still keep the file local.
        }
    }

    private record StoredState(String token, List<PlayerMessage> messages) {
    }
}
