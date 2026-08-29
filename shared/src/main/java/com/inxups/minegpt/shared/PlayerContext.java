package com.inxups.minegpt.shared;

/** A client-side snapshot attached to an AI chat message. */
public record PlayerContext(
        String playerName,
        String serverAddress,
        String dimension,
        double x,
        double y,
        double z,
        float health,
        int hunger,
        String gameMode) {
}
