package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MineGPTSkillTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesAndReadsMinecraftRootSkills() throws Exception {
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.initialize();
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("minegpt/skills/minegpt-guide.md")));

        Files.writeString(temporaryDirectory.resolve("minegpt/skills/caving.md"), "---\ndescription: Caving advice\n---\n# Caving\n");
        assertEquals(2, skills.list().size());
        assertEquals("Caving advice", skills.list().get(0).description());
        assertTrue(skills.read("caving.md").contains("# Caving"));

        Path defaultSkill = temporaryDirectory.resolve("minegpt/skills/minegpt-guide.md");
        Files.delete(defaultSkill);
        skills.list();
        assertTrue(Files.isRegularFile(defaultSkill));
    }

    @Test
    void listsAndReadsNestedSkillsUsingRelativePaths() throws Exception {
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.initialize();
        Path nestedSkill = temporaryDirectory.resolve("minegpt/skills/building/redstone/guide.md");
        Files.createDirectories(nestedSkill.getParent());
        Files.writeString(nestedSkill, "---\ndescription: Redstone building advice\n---\n# Redstone\n");

        assertTrue(skills.list().stream().anyMatch(skill -> skill.name().equals("building/redstone/guide.md")));
        assertTrue(skills.read("building/redstone/guide.md").contains("# Redstone"));
    }

    @Test
    void readsSkillsUpToTheExpandedSizeLimit() throws Exception {
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.initialize();
        Path largeSkill = temporaryDirectory.resolve("minegpt/skills/reference.md");
        Files.writeString(largeSkill, "x".repeat(256 * 1024));

        assertEquals(256 * 1024, skills.read("reference.md").length());
    }

    @Test
    void rejectsSkillPathsOutsideTheSkillsDirectory() throws Exception {
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.initialize();
        assertThrows(java.io.IOException.class, () -> skills.read("../outside.md"));
    }
}
