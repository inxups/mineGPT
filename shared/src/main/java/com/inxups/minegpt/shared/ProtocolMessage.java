package com.inxups.minegpt.shared;

/** One newline-delimited message exchanged between the Mod and the local bridge. */
public record ProtocolMessage(
        String type,
        String token,
        String messageId,
        PlayerMessage message,
        String text,
        String detail,
        String requestId,
        ChunkQuery chunkQuery,
        ChunkInfo chunkInfo,
        GameQuery gameQuery,
        GameQueryResult gameQueryResult) {

    public static ProtocolMessage hello(String token) {
        return new ProtocolMessage("hello", token, null, null, null, null, null, null, null, null, null);
    }

    public static ProtocolMessage helloAccepted() {
        return new ProtocolMessage("hello_accepted", null, null, null, null, null, null, null, null, null, null);
    }

    public static ProtocolMessage playerMessage(PlayerMessage message) {
        return new ProtocolMessage("player_message", null, message.id(), message, null, null, null, null, null, null, null);
    }

    public static ProtocolMessage accepted(String messageId) {
        return new ProtocolMessage("accepted", null, messageId, null, null, null, null, null, null, null, null);
    }

    public static ProtocolMessage reply(String messageId, String text) {
        return new ProtocolMessage("reply", null, messageId, null, text, null, null, null, null, null, null);
    }

    public static ProtocolMessage error(String detail) {
        return new ProtocolMessage("error", null, null, null, null, detail, null, null, null, null, null);
    }

    public static ProtocolMessage chunkInfoRequest(String requestId, ChunkQuery query) {
        return new ProtocolMessage("chunk_info_request", null, null, null, null, null, requestId, query, null, null, null);
    }

    public static ProtocolMessage chunkInfoResponse(String requestId, ChunkInfo info) {
        return new ProtocolMessage("chunk_info_response", null, null, null, null, null, requestId, null, info, null, null);
    }

    public static ProtocolMessage gameQueryRequest(String requestId, GameQuery query) {
        return new ProtocolMessage("game_query_request", null, null, null, null, null, requestId, null, null, query, null);
    }

    public static ProtocolMessage gameQueryResponse(String requestId, GameQueryResult result) {
        return new ProtocolMessage("game_query_response", null, null, null, null, null, requestId, null, null, null, result);
    }
}
