package com.inxups.minegpt.shared;

/** A request for a single chunk that is already loaded by the Minecraft client. */
public record ChunkQuery(Integer chunkX, Integer chunkZ) {
    public boolean usesPlayerChunk() {
        return chunkX == null && chunkZ == null;
    }

    public boolean isValid() {
        return (chunkX == null) == (chunkZ == null);
    }
}
