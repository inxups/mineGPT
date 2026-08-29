package com.inxups.minegpt.bridge;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/** Read-only, bounded access to the active Minecraft instance directory. */
final class GameDirectoryStore {
    static final int MAX_LIST_DEPTH = 8;
    static final int MAX_LIST_ENTRIES = 500;
    static final int MAX_READ_BYTES = 256 * 1024;
    private static final int DEFAULT_READ_BYTES = 64 * 1024;
    private static final int MAX_LOG_LINES = 1_000;

    private final SkillStore skills;

    GameDirectoryStore(SkillStore skills) {
        this.skills = skills;
    }

    DirectoryListing list(String relativePath, int depth) throws IOException {
        if (depth < 1 || depth > MAX_LIST_DEPTH) {
            throw new IOException("max_depth must be between 1 and " + MAX_LIST_DEPTH + ".");
        }
        Path root = gameRoot();
        Path directory = resolve(root, relativePath, true);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Path is not a directory: " + relativePath);
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.walk(directory, depth + 1)) {
            paths = stream
                    .filter(path -> !path.equals(directory))
                    .filter(path -> !Files.isSymbolicLink(path))
                    .limit(MAX_LIST_ENTRIES + 1L)
                    .toList();
        }
        boolean truncated = paths.size() > MAX_LIST_ENTRIES;
        List<FileEntry> entries = paths.stream()
                .limit(MAX_LIST_ENTRIES)
                .map(path -> fileEntry(root, path))
                .sorted(Comparator.comparing(FileEntry::path, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new DirectoryListing(relativeName(root, directory), depth, truncated, entries);
    }

    FileContent read(String relativePath, long offset, Integer requestedBytes, String requestedEncoding) throws IOException {
        if (offset < 0) {
            throw new IOException("offset must be zero or greater.");
        }
        int maxBytes = requestedBytes == null ? DEFAULT_READ_BYTES : requestedBytes;
        if (maxBytes < 1 || maxBytes > MAX_READ_BYTES) {
            throw new IOException("max_bytes must be between 1 and " + MAX_READ_BYTES + ".");
        }
        String encoding = requestedEncoding == null || requestedEncoding.isBlank()
                ? "utf8" : requestedEncoding.toLowerCase(Locale.ROOT);
        if (!encoding.equals("utf8") && !encoding.equals("base64")) {
            throw new IOException("encoding must be utf8 or base64.");
        }
        Path root = gameRoot();
        Path file = resolve(root, relativePath, false);
        long size = Files.size(file);
        byte[] bytes = readRange(file, offset, maxBytes);
        String content = encoding.equals("base64")
                ? Base64.getEncoder().encodeToString(bytes)
                : new String(bytes, StandardCharsets.UTF_8);
        return new FileContent(relativeName(root, file), size, offset, bytes.length,
                offset + bytes.length < size, encoding, content);
    }

    OptionsResult options() throws IOException {
        Path root = gameRoot();
        Path options = resolveIfRegularFile(root, "options.txt");
        if (options == null) {
            return OptionsResult.unavailable("options.txt does not exist in the active game directory.");
        }
        long size = Files.size(options);
        byte[] bytes = readRange(options, 0, MAX_READ_BYTES);
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\R")) {
            int separator = line.indexOf(':');
            if (separator > 0 && values.size() < MAX_LIST_ENTRIES) {
                values.put(line.substring(0, separator), line.substring(separator + 1));
            }
        }
        return new OptionsResult(true, null, relativeName(root, options),
                size > bytes.length || values.size() >= MAX_LIST_ENTRIES, values);
    }

    FileListing installedMods() throws IOException {
        return listChildren("mods", path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"));
    }

    FileListing savedWorlds() throws IOException {
        return listChildren("saves", path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS));
    }

    LogTail recentLog(int maxLines) throws IOException {
        if (maxLines < 1 || maxLines > MAX_LOG_LINES) {
            throw new IOException("max_lines must be between 1 and " + MAX_LOG_LINES + ".");
        }
        Path root = gameRoot();
        Path log = resolveIfRegularFile(root, "logs/latest.log");
        if (log == null) {
            return LogTail.unavailable("logs/latest.log does not exist in the active game directory.");
        }
        long size = Files.size(log);
        long offset = Math.max(0L, size - MAX_READ_BYTES);
        String content = new String(readRange(log, offset, MAX_READ_BYTES), StandardCharsets.UTF_8);
        List<String> allLines = new ArrayList<>(List.of(content.split("\\R", -1)));
        if (!allLines.isEmpty() && allLines.getLast().isEmpty()) {
            allLines.removeLast();
        }
        if (offset > 0 && !allLines.isEmpty()) {
            allLines.removeFirst();
        }
        int firstLine = Math.max(0, allLines.size() - maxLines);
        return new LogTail(true, null, relativeName(root, log), offset > 0 || firstLine > 0,
                allLines.subList(firstLine, allLines.size()));
    }

    private FileListing listChildren(String relativeDirectory, java.util.function.Predicate<Path> filter) throws IOException {
        Path root = gameRoot();
        Path directory = resolveIfDirectory(root, relativeDirectory);
        if (directory == null) {
            return FileListing.unavailable(relativeDirectory + " does not exist in the active game directory.");
        }
        List<Path> paths;
        try (Stream<Path> stream = Files.list(directory)) {
            paths = stream
                    .filter(path -> !Files.isSymbolicLink(path))
                    .filter(filter)
                    .limit(MAX_LIST_ENTRIES + 1L)
                    .toList();
        }
        boolean truncated = paths.size() > MAX_LIST_ENTRIES;
        List<FileEntry> entries = paths.stream()
                .limit(MAX_LIST_ENTRIES)
                .map(path -> fileEntry(root, path))
                .sorted(Comparator.comparing(FileEntry::path, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return new FileListing(true, null, relativeDirectory, truncated, entries);
    }

    private Path gameRoot() throws IOException {
        Path directory = skills.gameDirectory();
        if (directory == null) {
            throw new IOException("Minecraft client has not reported its game directory yet.");
        }
        if (!Files.isDirectory(directory)) {
            throw new IOException("The reported Minecraft game directory is unavailable.");
        }
        return directory.toRealPath();
    }

    private static Path resolve(Path root, String requestedPath, boolean allowEmpty) throws IOException {
        if (requestedPath == null || requestedPath.isBlank()) {
            if (allowEmpty) {
                return root;
            }
            throw new IOException("path is required.");
        }
        if (requestedPath.length() > 1_024 || requestedPath.indexOf('\\') >= 0) {
            throw new IOException("path must be a relative forward-slash path inside the active game directory.");
        }
        Path relativePath;
        try {
            relativePath = Path.of(requestedPath);
        } catch (RuntimeException exception) {
            throw new IOException("path must be a relative path inside the active game directory.", exception);
        }
        if (relativePath.isAbsolute()) {
            throw new IOException("path must be relative to the active game directory.");
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root) || !Files.exists(candidate)) {
            throw new IOException("Path not found in the active game directory: " + requestedPath);
        }
        Path resolved = candidate.toRealPath();
        if (!resolved.startsWith(root)) {
            throw new IOException("Path resolves outside the active game directory.");
        }
        return resolved;
    }

    private static Path resolveIfRegularFile(Path root, String relativePath) throws IOException {
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root) || !Files.exists(candidate) || !Files.isRegularFile(candidate)) {
            return null;
        }
        Path resolved = candidate.toRealPath();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static Path resolveIfDirectory(Path root, String relativePath) throws IOException {
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(root) || !Files.isDirectory(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        Path resolved = candidate.toRealPath();
        return resolved.startsWith(root) ? resolved : null;
    }

    private static byte[] readRange(Path file, long offset, int maxBytes) throws IOException {
        long size = Files.size(file);
        if (offset >= size) {
            return new byte[0];
        }
        int bytesToRead = (int) Math.min(maxBytes, size - offset);
        ByteBuffer buffer = ByteBuffer.allocate(bytesToRead);
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            channel.position(offset);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // Keep reading until the requested range is complete.
            }
        }
        if (buffer.position() == buffer.capacity()) {
            return buffer.array();
        }
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }

    private static FileEntry fileEntry(Path root, Path path) {
        String kind = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) ? "directory"
                : Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? "file" : "other";
        try {
            return new FileEntry(relativeName(root, path), kind,
                    kind.equals("file") ? Files.size(path) : 0L,
                    Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toInstant().toEpochMilli());
        } catch (IOException exception) {
            return new FileEntry(relativeName(root, path), kind, -1L, -1L);
        }
    }

    private static String relativeName(Path root, Path path) {
        return root.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
    }

    record DirectoryListing(String path, int maxDepth, boolean truncated, List<FileEntry> entries) {
    }

    record FileEntry(String path, String kind, long sizeBytes, long modifiedAtEpochMillis) {
    }

    record FileContent(String path, long sizeBytes, long offset, int bytesReturned,
                       boolean truncated, String encoding, String content) {
    }

    record OptionsResult(boolean available, String detail, String path, boolean truncated, Map<String, String> values) {
        static OptionsResult unavailable(String detail) {
            return new OptionsResult(false, detail, null, false, Map.of());
        }
    }

    record FileListing(boolean available, String detail, String path, boolean truncated, List<FileEntry> entries) {
        static FileListing unavailable(String detail) {
            return new FileListing(false, detail, null, false, List.of());
        }
    }

    record LogTail(boolean available, String detail, String path, boolean truncated, List<String> lines) {
        static LogTail unavailable(String detail) {
            return new LogTail(false, detail, null, false, List.of());
        }
    }
}
