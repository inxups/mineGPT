package com.inxups.minegpt.shared;

/**
 * Read-only summary of one client-loaded chunk. Heights and block IDs use a row-major
 * 16 by 16 layout: {@code index = localZ * 16 + localX}.
 */
public record ChunkInfo(
        boolean loaded,
        String detail,
        String dimension,
        Integer chunkX,
        Integer chunkZ,
        Long gameTime,
        Integer minBuildHeight,
        Integer maxBuildHeight,
        int[] surfaceHeights,
        String[] surfaceBlocks) {

    public static ChunkInfo unavailable(String detail) {
        return new ChunkInfo(false, detail, null, null, null, null, null, null, null, null);
    }
}
