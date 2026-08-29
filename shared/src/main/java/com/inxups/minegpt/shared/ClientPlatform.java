package com.inxups.minegpt.shared;

import java.nio.file.Path;

/** Loader adapter for the client-only Minecraft APIs. */
public interface ClientPlatform {
    PlayerContext captureContext();

    /** Must only be called from the Minecraft client thread. */
    ChunkInfo readChunkInfo(ChunkQuery query);

    /** Must only be called from the Minecraft client thread. */
    GameQueryResult readGameQuery(GameQuery query);

    /** The game instance directory used by this client, not the mod config directory. */
    Path gameDirectory();

    void executeOnClientThread(Runnable action);

    void showLocalMessage(String message);
}
