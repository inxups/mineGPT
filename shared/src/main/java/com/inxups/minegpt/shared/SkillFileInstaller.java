package com.inxups.minegpt.shared;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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

    /** Downloads and installs one public GitHub Markdown skill without replacing existing files. */
    public static ImportedSkill importGitHubSkillUrl(Path gameDirectory, String githubUrl, String destinationPath)
            throws IOException, InterruptedException {
        String destination = destinationPath == null || destinationPath.isBlank()
                ? GitHubSkillDownload.defaultInstallPathFromUrl(githubUrl) : destinationPath;
        GitHubSkillDownload.DownloadedSkill downloaded = GitHubSkillDownload.downloadUrl(githubUrl);
        Path installed = writeImportedSkill(gameDirectory, destination, downloaded.contents());
        return new ImportedSkill(downloaded.repository(), downloaded.ref(), downloaded.sourceUrl(),
                skillsDirectory(gameDirectory).relativize(installed).toString().replace('\\', '/'));
    }

    private static Path writeImportedSkill(Path gameDirectory, String name, byte[] contents) throws IOException {
        if (contents == null || contents.length == 0 || contents.length > GitHubSkillDownload.MAX_SKILL_BYTES) {
            throw new IOException("Skill files must contain at most " + GitHubSkillDownload.MAX_SKILL_BYTES + " bytes.");
        }
        Path directory = ensureSafeSkillsDirectory(gameDirectory);
        Path target = resolveSkillPath(directory, name);
        ensureSafeDirectories(directory, target.getParent());
        if (Files.isSymbolicLink(target)) {
            throw new IOException("Skill target must not be a symbolic link.");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException("Skill already exists: " + name);
        }
        Path temporary = Files.createTempFile(target.getParent(), ".minegpt-import-", ".tmp");
        try {
            Files.write(temporary, contents, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    private static Path ensureSafeSkillsDirectory(Path gameDirectory) throws IOException {
        if (gameDirectory == null || !gameDirectory.isAbsolute()) {
            throw new IOException("Minecraft game directory is unavailable.");
        }
        Path gameRoot = gameDirectory.toRealPath();
        Path directory = gameRoot;
        for (String segment : new String[] {"minegpt", "skills"}) {
            directory = directory.resolve(segment);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("MineGPT skills directory must not contain symbolic links or files.");
                }
            } else {
                Files.createDirectory(directory);
            }
        }
        return directory;
    }

    private static Path resolveSkillPath(Path directory, String name) throws IOException {
        if (name == null || name.isBlank() || name.length() > 512 || name.indexOf('\\') >= 0) {
            throw new IOException("destination_path must be a relative Markdown path in the MineGPT skills directory.");
        }
        Path relativePath;
        try {
            relativePath = Path.of(name);
        } catch (RuntimeException exception) {
            throw new IOException("destination_path must be a relative Markdown path in the MineGPT skills directory.", exception);
        }
        String fileName = relativePath.getFileName() == null ? "" : relativePath.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        if (relativePath.isAbsolute() || relativePath.getNameCount() > 9 || !fileName.endsWith(".md") || fileName.length() <= 3) {
            throw new IOException("destination_path must be a Markdown path no deeper than eight directories.");
        }
        Path target = directory.resolve(relativePath).normalize();
        if (!target.startsWith(directory)) {
            throw new IOException("destination_path must stay inside the MineGPT skills directory.");
        }
        return target;
    }

    private static void ensureSafeDirectories(Path directory, Path targetParent) throws IOException {
        Path current = directory;
        for (Path segment : directory.relativize(targetParent)) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Skill parent directories must not be symbolic links or files.");
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    public record ImportedSkill(String repository, String ref, String sourceUrl, String installedPath) {
    }
}
