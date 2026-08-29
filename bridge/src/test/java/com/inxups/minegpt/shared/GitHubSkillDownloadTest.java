package com.inxups.minegpt.shared;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class GitHubSkillDownloadTest {
    @Test
    void buildsAConstrainedRawGithubUrl() throws Exception {
        assertEquals("https://raw.githubusercontent.com/example/minecraft-skills/v1.2.0/exploration/scouting.md",
                GitHubSkillDownload.rawGithubUri("example/minecraft-skills", "exploration/scouting.md", "v1.2.0").toString());
        assertEquals("https://raw.githubusercontent.com/example/minecraft-skills/main/.github/skills/scouting.md",
                GitHubSkillDownload.rawGithubUri("example/minecraft-skills", ".github/skills/scouting.md", null).toString());
    }

    @Test
    void acceptsGithubFilePagesAndRawUrls() throws Exception {
        assertEquals("https://raw.githubusercontent.com/example/minecraft-skills/main/skills/scouting.md",
                GitHubSkillDownload.rawGithubUriFromUrl("https://github.com/example/minecraft-skills/blob/main/skills/scouting.md")
                        .toString());
        assertEquals("aiq-deploy/SKILL.md",
                GitHubSkillDownload.defaultInstallPathFromUrl(
                        "https://github.com/NVIDIA/skills/blob/main/skills/aiq-deploy/SKILL.md"));
        assertEquals("https://raw.githubusercontent.com/example/minecraft-skills/main/skills/scouting.md",
                GitHubSkillDownload.rawGithubUriFromUrl("https://raw.githubusercontent.com/example/minecraft-skills/main/skills/scouting.md")
                        .toString());
    }

    @Test
    void rejectsUnsafeGithubRepositoryAndPathInputs() {
        assertThrows(java.io.IOException.class,
                () -> GitHubSkillDownload.rawGithubUri("example/../../etc", "skill.md", "main"));
        assertThrows(java.io.IOException.class,
                () -> GitHubSkillDownload.rawGithubUri("example/skills", "../skill.md", "main"));
        assertThrows(java.io.IOException.class,
                () -> GitHubSkillDownload.rawGithubUri("example/skills", "skill.txt", "main"));
        assertThrows(java.io.IOException.class,
                () -> GitHubSkillDownload.rawGithubUri("example/skills", "skill.md", "feature/new-skill"));
    }
}
