package com.inxups.minegpt.shared;

/** Loader adapter for the client-only Minecraft APIs. */
public interface ClientPlatform {
    PlayerContext captureContext();

    void executeOnClientThread(Runnable action);

    void showLocalMessage(String message);
}
