package com.inxups.minegpt.shared;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/** JSON Lines codec shared by both game loaders and the bridge. */
public final class ProtocolCodec {
    public static final int MAX_LINE_LENGTH = 32 * 1024;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private ProtocolCodec() {
    }

    public static String encode(ProtocolMessage message) {
        String line = GSON.toJson(message);
        if (line.length() > MAX_LINE_LENGTH) {
            throw new IllegalArgumentException("MineGPT protocol message exceeds 32 KiB");
        }
        return line;
    }

    public static ProtocolMessage decode(String line) {
        if (line == null || line.length() > MAX_LINE_LENGTH) {
            throw new IllegalArgumentException("Invalid MineGPT protocol line length");
        }
        ProtocolMessage message = GSON.fromJson(line, ProtocolMessage.class);
        if (message == null || message.type() == null || message.type().isBlank()) {
            throw new IllegalArgumentException("MineGPT protocol message has no type");
        }
        return message;
    }

    public static String toJson(Object value) {
        return GSON.toJson(value);
    }
}
