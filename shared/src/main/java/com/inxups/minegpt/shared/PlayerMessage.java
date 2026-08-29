package com.inxups.minegpt.shared;

/** A message submitted by the Minecraft client to the local bridge. */
public record PlayerMessage(String id, String text, PlayerContext context, long createdAtEpochMillis) {
}
