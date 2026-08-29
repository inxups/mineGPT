package com.inxups.minegpt.shared;

/** A JSON payload returned by a client-side read-only world query. */
public record GameQueryResult(boolean available, String detail, String dataJson) {
    public static GameQueryResult available(Object value) {
        return new GameQueryResult(true, null, ProtocolCodec.toJson(value));
    }

    public static GameQueryResult unavailable(String detail) {
        return new GameQueryResult(false, detail, null);
    }
}
