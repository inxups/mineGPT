package com.inxups.minegpt.shared;

/** Loader adapter for the client-only Minecraft APIs. */
public interface ClientPlatform {
    PlayerContext captureContext();

    /** Must only be called from the Minecraft client thread. */
    ChunkInfo readChunkInfo(ChunkQuery query);

    /** Must only be called from the Minecraft client thread. */
    GameQueryResult readGameQuery(GameQuery query);

    void executeOnClientThread(Runnable action);

    void showLocalMessage(String message);
}
