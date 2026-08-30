package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
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

    @Test
    void searchesRelevantLocalModpackDataWithoutLeavingTheInstance() throws Exception {
        Path gameDirectory = temporaryDirectory.resolve("instance");
        SkillStore skills = new SkillStore(gameDirectory);
        GameDirectoryStore files = new GameDirectoryStore(skills);
        write(gameDirectory.resolve("kubejs/server_scripts/recipes.js"),
                "event.recipes.createMixing('example:sky_ingot', ['example:dust'])\n");
        write(gameDirectory.resolve("config/custom-recipes.json"),
                "{\"output\":\"example:sky_ingot\"}\n");
        write(gameDirectory.resolve("config/ftbquests/quests/chapter.snbt"),
                "{rewards:[{item:\"example:sky_ingot\"}]}\n");
        write(gameDirectory.resolve("saves/local/datapacks/pack/data/example/recipe/sky_ingot.json"),
                "{\"result\":{\"id\":\"example:sky_ingot\"}}\n");

        GameDirectoryStore.ModpackSearchResult all = files.searchModpackFiles("EXAMPLE:SKY_INGOT", "all");
        assertTrue(all.matches().stream().anyMatch(match -> match.path().equals("kubejs/server_scripts/recipes.js")));
        assertTrue(all.matches().stream().anyMatch(match -> match.path().equals("config/ftbquests/quests/chapter.snbt")));
        assertTrue(all.matches().stream().anyMatch(match -> match.path().equals("saves/local/datapacks/pack/data/example/recipe/sky_ingot.json")));

        GameDirectoryStore.ModpackSearchResult kubeJs = files.searchModpackFiles("example:sky_ingot", "kubejs");
        assertEquals(1, kubeJs.matches().size());
        assertEquals("kubejs/server_scripts/recipes.js", kubeJs.matches().getFirst().path());
        assertThrows(java.io.IOException.class, () -> files.searchModpackFiles("example:sky_ingot", "outside"));
        skills.close();
    }

    @Test
    void inspectsOnlyDirectModJarsWithBoundedResourceAndClassStringMatches() throws Exception {
        Path gameDirectory = temporaryDirectory.resolve("instance");
        SkillStore skills = new SkillStore(gameDirectory);
        GameDirectoryStore files = new GameDirectoryStore(skills);
        Path jar = gameDirectory.resolve("mods/example-mod.jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            addJarEntry(output, "fabric.mod.json", "{\"id\":\"examplemod\",\"name\":\"Example Mod\"}");
            addJarEntry(output, "data/example/recipe/sky_ingot.json", "{\"result\":\"example:sky_ingot\"}");
            addJarEntry(output, "com/example/Recipe.class", "not-bytecode\u0000example:sky_ingot\u0000serializer");
        }

        GameDirectoryStore.ModJarInspection inspection = files.inspectModJar("example-mod.jar", "example:sky_ingot");
        assertTrue(inspection.available());
        assertTrue(inspection.metadata().stream().anyMatch(match -> match.path().equals("fabric.mod.json")));
        assertTrue(inspection.matches().stream().anyMatch(match -> match.path().equals("data/example/recipe/sky_ingot.json")));
        assertTrue(inspection.matches().stream().anyMatch(match -> match.path().equals("com/example/Recipe.class")
                && match.kind().equals("class-string")));
        assertThrows(java.io.IOException.class, () -> files.inspectModJar("../example-mod.jar", null));
        skills.close();
    }

    private static void write(Path path, String content) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static void addJarEntry(JarOutputStream output, String name, String content) throws Exception {
        output.putNextEntry(new JarEntry(name));
        output.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        output.closeEntry();
    }
}
