package com.inxups.minegpt.shared;

/** One newline-delimited message exchanged between the Mod and the local bridge. */
public record ProtocolMessage(
        String type,
        String token,
        String messageId,
        PlayerMessage message,
        String text,
        String detail) {

    public static ProtocolMessage hello(String token) {
        return new ProtocolMessage("hello", token, null, null, null, null);
    }

    public static ProtocolMessage helloAccepted() {
        return new ProtocolMessage("hello_accepted", null, null, null, null, null);
    }

    public static ProtocolMessage playerMessage(PlayerMessage message) {
        return new ProtocolMessage("player_message", null, message.id(), message, null, null);
    }

    public static ProtocolMessage accepted(String messageId) {
        return new ProtocolMessage("accepted", null, messageId, null, null, null);
    }

    public static ProtocolMessage reply(String messageId, String text) {
        return new ProtocolMessage("reply", null, messageId, null, text, null);
    }

    public static ProtocolMessage error(String detail) {
        return new ProtocolMessage("error", null, null, null, null, detail);
    }
}
