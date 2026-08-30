package com.inxups.minegpt.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillFileInstallerTest {
    @TempDir
    Path gameDirectory;

    @Test
    void installsAndRestoresBuiltinSkillsWithoutReplacingExistingContent() throws Exception {
        assertEquals(3, SkillFileInstaller.ensureBuiltinSkills(gameDirectory).size());
        Path guide = gameDirectory.resolve("minegpt/skills/minegpt-guide.md");
        Path liveData = gameDirectory.resolve("minegpt/skills/live-data/SKILL.md");
        Path recipe = gameDirectory.resolve("minegpt/skills/modpack-recipe-investigation/SKILL.md");
        assertTrue(Files.isRegularFile(guide));
        assertTrue(Files.isRegularFile(liveData));
        assertTrue(Files.isRegularFile(recipe));

        Files.writeString(liveData, "# Custom live data workflow\n");
        SkillFileInstaller.ensureBuiltinSkills(gameDirectory);
        assertEquals("# Custom live data workflow\n", Files.readString(liveData));

        Files.delete(guide);
        Files.delete(recipe);
        SkillFileInstaller.ensureBuiltinSkills(gameDirectory);
        assertTrue(Files.readString(guide).contains("# MineGPT Skill"));
        assertTrue(Files.readString(recipe).contains("# Modpack Recipe Investigation"));
    }
}
