package com.inxups.minegpt.bridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** User-editable Markdown skills stored alongside the active Minecraft instance. */
final class SkillStore implements AutoCloseable {
    static final String DEFAULT_SKILL_FILE = "minegpt-guide.md";
    private static final long MAX_SKILL_BYTES = 256 * 1024;
    private static final int MAX_SKILL_NESTING_DEPTH = 8;

    private volatile Path skillsDirectory;
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "minegpt-skill-monitor");
        thread.setDaemon(true);
        return thread;
    });

    SkillStore() {
        skillsDirectory = null;
        startMonitor();
    }

    SkillStore(Path minecraftDirectory) {
        skillsDirectory = skillsPath(minecraftDirectory);
        startMonitor();
    }

    Path directory() {
        return skillsDirectory;
    }

    Path gameDirectory() {
        Path currentDirectory = skillsDirectory;
        return currentDirectory == null ? null : currentDirectory.getParent().getParent();
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

    synchronized void initialize() throws IOException {
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

    private void startMonitor() {
        monitor.scheduleWithFixedDelay(() -> {
            if (skillsDirectory != null) {
                try {
                    initialize();
                } catch (IOException ignored) {
                    // The directory may be temporarily unavailable while an instance is starting.
                }
            }
        }, 2, 2, TimeUnit.SECONDS);
    }

    List<SkillSummary> list() throws IOException {
        if (skillsDirectory == null) {
            return List.of();
        }
        initialize();
        try (Stream<Path> entries = Files.walk(skillsDirectory, MAX_SKILL_NESTING_DEPTH + 1)) {
            return entries
                    .filter(Files::isRegularFile)
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(SkillStore::isMarkdown)
                    .sorted(Comparator.comparing(this::relativeName, String.CASE_INSENSITIVE_ORDER))
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
            if (Files.size(skill) > MAX_SKILL_BYTES) {
                return new SkillSummary(relativeName(skill), "Skill file exceeds " + MAX_SKILL_BYTES + " bytes.");
            }
            return new SkillSummary(relativeName(skill), description(Files.readString(skill, StandardCharsets.UTF_8)));
        } catch (IOException exception) {
            return new SkillSummary(relativeName(skill), "Could not read this skill file.");
        }
    }

    private Path resolveSkill(String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 512 || name.indexOf('\\') >= 0) {
            throw new IOException("name must be a relative Markdown path in the MineGPT skills directory.");
        }
        Path relativePath;
        try {
            relativePath = Path.of(name);
        } catch (RuntimeException exception) {
            throw new IOException("name must be a relative Markdown path in the MineGPT skills directory.", exception);
        }
        if (relativePath.isAbsolute() || relativePath.getNameCount() > MAX_SKILL_NESTING_DEPTH + 1
                || !isMarkdown(relativePath)) {
            throw new IOException("name must be a relative Markdown path in the MineGPT skills directory.");
        }
        Path skill = skillsDirectory.resolve(relativePath).normalize();
        if (!skill.startsWith(skillsDirectory) || !Files.isRegularFile(skill) || Files.isSymbolicLink(skill)) {
            throw new IOException("Skill not found: " + name);
        }
        return skill;
    }

    private String relativeName(Path skill) {
        return skillsDirectory.relativize(skill).toString().replace(File.separatorChar, '/');
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

    @Override
    public void close() {
        monitor.shutdownNow();
    }
}
