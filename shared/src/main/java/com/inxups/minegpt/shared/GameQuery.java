package com.inxups.minegpt.shared;

/** A bounded, read-only data request issued by the local Bridge. */
public record GameQuery(
        String kind,
        Integer x,
        Integer y,
        Integer z,
        Integer chunkX,
        Integer chunkZ,
        Integer sectionY,
        Integer radius) {

    public static GameQuery playerState() {
        return new GameQuery("player_state", null, null, null, null, null, null, null);
    }

    public static GameQuery target() {
        return new GameQuery("target", null, null, null, null, null, null, null);
    }

    public static GameQuery inventory() {
        return new GameQuery("inventory", null, null, null, null, null, null, null);
    }

    public static GameQuery nearbyEntities(int radius) {
        return new GameQuery("nearby_entities", null, null, null, null, null, null, radius);
    }

    public static GameQuery block(int x, int y, int z) {
        return new GameQuery("block", x, y, z, null, null, null, null);
    }

    public static GameQuery chunkSection(int chunkX, int chunkZ, int sectionY) {
        return new GameQuery("chunk_section", null, null, null, chunkX, chunkZ, sectionY, null);
    }

    public static GameQuery environment() {
        return new GameQuery("environment", null, null, null, null, null, null, null);
    }
}
