package com.inxups.minegpt.shared;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Installs the default user-editable skill from the client Mod at game startup. */
public final class SkillFileInstaller {
    public static final String DEFAULT_SKILL_FILE = "minegpt-guide.md";
    private static final String TEMPLATE_RESOURCE = "/MINEGPT_CLIENT_SKILL.md";

    private SkillFileInstaller() {
    }

    public static Path skillsDirectory(Path gameDirectory) throws IOException {
        if (gameDirectory == null || !gameDirectory.isAbsolute()) {
            throw new IOException("Minecraft game directory is unavailable.");
        }
        return gameDirectory.toAbsolutePath().normalize().resolve("minegpt").resolve("skills");
    }

    public static Path ensureDefaultSkill(Path gameDirectory) throws IOException {
        Path directory = skillsDirectory(gameDirectory);
        Files.createDirectories(directory);
        Path target = directory.resolve(DEFAULT_SKILL_FILE);
        if (Files.exists(target)) {
            return target;
        }
        try (InputStream input = SkillFileInstaller.class.getResourceAsStream(TEMPLATE_RESOURCE)) {
            if (input == null) {
                throw new IOException("MineGPT client skill template is missing from the Mod.");
            }
            Files.writeString(target, new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        return target;
    }
}
