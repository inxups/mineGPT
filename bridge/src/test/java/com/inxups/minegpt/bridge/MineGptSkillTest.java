package com.inxups.minegpt.bridge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MineGptSkillTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void initializesAndReadsMinecraftRootSkills() throws Exception {
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.initialize();
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve(".minecraft/minegpt/skills/minegpt-guide.md")));

        Files.writeString(temporaryDirectory.resolve(".minecraft/minegpt/skills/caving.md"), "---\ndescription: Caving advice\n---\n# Caving\n");
        assertEquals(2, skills.list().size());
        assertEquals("Caving advice", skills.list().get(0).description());
        assertTrue(skills.read("caving.md").contains("# Caving"));
    }

    @Test
    void rejectsSkillPathsOutsideTheSkillsDirectory() throws Exception {
        SkillStore skills = new SkillStore(temporaryDirectory);
        skills.initialize();
        assertThrows(java.io.IOException.class, () -> skills.read("../outside.md"));
    }
}
