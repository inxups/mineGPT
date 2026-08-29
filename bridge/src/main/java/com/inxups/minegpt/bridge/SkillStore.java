package com.inxups.minegpt.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/** User-editable Markdown skills stored alongside the active Minecraft instance. */
final class SkillStore {
    static final String DEFAULT_SKILL_FILE = "minegpt-guide.md";
    private static final long MAX_SKILL_BYTES = 32 * 1024;

    private volatile Path skillsDirectory;

    SkillStore() {
        skillsDirectory = null;
    }

    SkillStore(Path minecraftDirectory) {
        skillsDirectory = skillsPath(minecraftDirectory);
    }

    Path directory() {
        return skillsDirectory;
    }

    synchronized void setMinecraftDirectory(Path minecraftDirectory) throws IOException {
        if (minecraftDirectory == null || !minecraftDirectory.isAbsolute()) {
            return;
        }
        skillsDirectory = skillsPath(minecraftDirectory);
        initialize();
    }

    private static Path skillsPath(Path instanceDirectory) {
        return instanceDirectory.toAbsolutePath().normalize().resolve("minegpt").resolve("skills");
    }

    void initialize() throws IOException {
        if (skillsDirectory == null) {
            throw new IOException("Minecraft client has not reported its game directory yet.");
        }
        Files.createDirectories(skillsDirectory);
        Path defaultSkill = skillsDirectory.resolve(DEFAULT_SKILL_FILE);
        if (Files.exists(defaultSkill)) {
            return;
        }
        try (InputStream input = SkillStore.class.getResourceAsStream("/MINEGPT_SKILL.md")) {
            if (input == null) {
                throw new IOException("Bundled MineGPT skill template is missing.");
            }
            Files.writeString(defaultSkill, new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
    }

    List<SkillSummary> list() throws IOException {
        if (skillsDirectory == null) {
            return List.of();
        }
        initialize();
        try (Stream<Path> entries = Files.list(skillsDirectory)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(SkillStore::isMarkdown)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .map(this::summary)
                    .toList();
        }
    }

    String read(String name) throws IOException {
        initialize();
        Path skill = resolveSkill(name);
        long size = Files.size(skill);
        if (size > MAX_SKILL_BYTES) {
            throw new IOException("Skill files must not exceed " + MAX_SKILL_BYTES + " bytes.");
        }
        return Files.readString(skill, StandardCharsets.UTF_8);
    }

    private SkillSummary summary(Path skill) {
        try {
            return new SkillSummary(skill.getFileName().toString(), description(Files.readString(skill, StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            return new SkillSummary(skill.getFileName().toString(), "Could not read this skill file.");
        }
    }

    private Path resolveSkill(String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 128 || !isMarkdown(Path.of(name))) {
            throw new IOException("name must be a Markdown filename in the MineGPT skills directory.");
        }
        Path fileName = Path.of(name).getFileName();
        if (fileName == null || !fileName.toString().equals(name)) {
            throw new IOException("Skill names cannot contain a path.");
        }
        Path skill = skillsDirectory.resolve(fileName).normalize();
        if (!skill.getParent().equals(skillsDirectory) || !Files.isRegularFile(skill)) {
            throw new IOException("Skill not found: " + name);
        }
        return skill;
    }

    private static boolean isMarkdown(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".md") && name.length() > 3;
    }

    private static String description(String document) {
        String[] lines = document.replace("\r", "").split("\n");
        boolean frontMatter = lines.length > 0 && lines[0].trim().equals("---");
        if (frontMatter) {
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index].trim();
                if (line.equals("---")) {
                    break;
                }
                if (line.startsWith("description:")) {
                    return limit(line.substring("description:".length()).trim());
                }
            }
        }
        for (String line : lines) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#") && !trimmed.equals("---")) {
                return limit(trimmed);
            }
        }
        return "No description provided.";
    }

    private static String limit(String value) {
        return value.length() <= 240 ? value : value.substring(0, 237) + "...";
    }

    record SkillSummary(String name, String description) {
    }
}
