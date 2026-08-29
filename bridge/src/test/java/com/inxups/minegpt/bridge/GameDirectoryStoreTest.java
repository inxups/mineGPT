package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameDirectoryStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void recursivelyListsAndReadsOnlyFilesInsideTheActiveGameDirectory() throws Exception {
        Path gameDirectory = temporaryDirectory.resolve("instance");
        SkillStore skills = new SkillStore(gameDirectory);
        GameDirectoryStore files = new GameDirectoryStore(skills);
        Path settings = gameDirectory.resolve("config/example/nested-settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"enabled\":true}");

        GameDirectoryStore.DirectoryListing listing = files.list(null, 4);
        assertTrue(listing.entries().stream().anyMatch(entry -> entry.path().equals("config/example/nested-settings.json")));
        assertEquals("{\"enabled\":true}", files.read("config/example/nested-settings.json", 0, null, "utf8").content());
        assertEquals(Base64.getEncoder().encodeToString("true".getBytes()),
                files.read("config/example/nested-settings.json", 11, 4, "base64").content());
        assertThrows(java.io.IOException.class, () -> files.read("../outside.txt", 0, null, "utf8"));
        skills.close();
    }

    @Test
    void returnsCommonInstanceDataWithBoundedResults() throws Exception {
        Path gameDirectory = temporaryDirectory.resolve("instance");
        SkillStore skills = new SkillStore(gameDirectory);
        GameDirectoryStore files = new GameDirectoryStore(skills);
        Files.createDirectories(gameDirectory.resolve("mods"));
        Files.createDirectories(gameDirectory.resolve("saves/creative-world"));
        Files.createDirectories(gameDirectory.resolve("logs"));
        Files.writeString(gameDirectory.resolve("mods/example.jar"), "jar");
        Files.writeString(gameDirectory.resolve("options.txt"), "renderDistance:12\nchatVisibility:0\n");
        Files.writeString(gameDirectory.resolve("logs/latest.log"), "first\nsecond\nthird\n");

        assertEquals("12", files.options().values().get("renderDistance"));
        assertTrue(files.installedMods().entries().stream().anyMatch(entry -> entry.path().equals("mods/example.jar")));
        assertTrue(files.savedWorlds().entries().stream().anyMatch(entry -> entry.path().equals("saves/creative-world")));
        assertEquals(java.util.List.of("second", "third"), files.recentLog(2).lines());
        skills.close();
    }

    @Test
    void reportsAbsentOptionalDirectoriesWithoutFailingTheTool() throws Exception {
        SkillStore skills = new SkillStore(temporaryDirectory.resolve("instance"));
        GameDirectoryStore files = new GameDirectoryStore(skills);
        skills.initialize();

        assertFalse(files.installedMods().available());
        assertFalse(files.savedWorlds().available());
        assertFalse(files.options().available());
        assertFalse(files.recentLog(200).available());
        skills.close();
    }
}
