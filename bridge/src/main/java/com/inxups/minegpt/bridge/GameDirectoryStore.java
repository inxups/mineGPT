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
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/** Read-only, bounded access to the active Minecraft instance directory. */
final class GameDirectoryStore {
    static final int MAX_LIST_DEPTH = 8;
    static final int MAX_LIST_ENTRIES = 500;
    static final int MAX_READ_BYTES = 256 * 1024;
    static final int MAX_MODPACK_QUERY_LENGTH = 256;
    static final int MAX_MOD_JAR_NAME_LENGTH = 255;
    private static final int DEFAULT_READ_BYTES = 64 * 1024;
    private static final int MAX_LOG_LINES = 1_000;
    private static final int MAX_MODPACK_SEARCH_DEPTH = 8;
    private static final int MAX_MODPACK_SEARCHED_FILES = 2_000;
    private static final int MAX_MODPACK_SEARCH_FILE_BYTES = 512 * 1024;
    private static final int MAX_MODPACK_SEARCH_MATCHES = 100;
    private static final int MAX_JAR_ENTRIES_SCANNED = 5_000;
    private static final int MAX_JAR_ENTRY_BYTES = 512 * 1024;
    private static final int MAX_JAR_MATCHES = 100;
    private static final int MAX_SNIPPET_CHARACTERS = 600;
    private static final Set<String> MODPACK_TEXT_EXTENSIONS = Set.of(
            ".cfg", ".json", ".js", ".mcfunction", ".properties", ".snbt", ".toml", ".txt", ".yaml", ".yml", ".zs");
    private static final Set<String> JAR_TEXT_EXTENSIONS = Set.of(
            ".json", ".lang", ".mcmeta", ".properties", ".snbt", ".toml", ".txt");
    private static final List<String> MOD_METADATA_ENTRIES = List.of(
            "META-INF/neoforge.mods.toml", "META-INF/mods.toml", "fabric.mod.json", "quilt.mod.json", "mcmod.info");

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

    ModpackSearchResult searchModpackFiles(String query, String requestedScope) throws IOException {
        String needle = normalizeQuery(query, "query");
        String scope = normalizeScope(requestedScope);
        Path root = gameRoot();
        List<Path> directories = modpackDirectories(root, scope);
        List<TextMatch> matches = new ArrayList<>();
        Set<Path> visitedFiles = new HashSet<>();
        int searchedFiles = 0;
        int skippedLargeFiles = 0;
        boolean truncated = false;

        for (Path directory : directories) {
            if (searchedFiles >= MAX_MODPACK_SEARCHED_FILES || matches.size() >= MAX_MODPACK_SEARCH_MATCHES) {
                truncated = true;
                break;
            }
            try (Stream<Path> stream = Files.walk(directory, MAX_MODPACK_SEARCH_DEPTH + 1)) {
                java.util.Iterator<Path> iterator = stream.iterator();
                while (iterator.hasNext()) {
                    Path candidate = iterator.next();
                    if (searchedFiles >= MAX_MODPACK_SEARCHED_FILES || matches.size() >= MAX_MODPACK_SEARCH_MATCHES) {
                        truncated = true;
                        break;
                    }
                    if (!isSearchableTextFile(candidate) || !visitedFiles.add(candidate.toAbsolutePath().normalize())) {
                        continue;
                    }
                    Path file;
                    try {
                        file = candidate.toRealPath();
                    } catch (IOException ignored) {
                        continue;
                    }
                    if (!file.startsWith(root)) {
                        continue;
                    }
                    searchedFiles++;
                    long size = Files.size(file);
                    if (size > MAX_MODPACK_SEARCH_FILE_BYTES) {
                        skippedLargeFiles++;
                        continue;
                    }
                    addTextMatches(root, file, needle, matches);
                }
            }
        }
        return new ModpackSearchResult(scope, needle, directories.stream().map(path -> relativeName(root, path)).toList(),
                searchedFiles, skippedLargeFiles, truncated, List.copyOf(matches));
    }

    ModJarInspection inspectModJar(String jarName, String query) throws IOException {
        validateJarName(jarName);
        String needle = query == null || query.isBlank() ? null : normalizeQuery(query, "query");
        Path root = gameRoot();
        Path modsDirectory = resolveIfDirectory(root, "mods");
        if (modsDirectory == null) {
            return ModJarInspection.unavailable("mods does not exist in the active game directory.");
        }
        Path candidate = modsDirectory.resolve(jarName).normalize();
        if (!candidate.startsWith(modsDirectory) || !Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(candidate)) {
            return ModJarInspection.unavailable("Mod JAR not found directly under mods/: " + jarName);
        }
        Path jarPath = candidate.toRealPath();
        if (!jarPath.startsWith(modsDirectory)) {
            return ModJarInspection.unavailable("Mod JAR resolves outside the active mods directory.");
        }

        List<JarEntryMatch> metadata = new ArrayList<>();
        List<JarEntryMatch> matches = new ArrayList<>();
        int entriesScanned = 0;
        boolean truncated = false;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            for (String metadataName : MOD_METADATA_ENTRIES) {
                JarEntry entry = jar.getJarEntry(metadataName);
                if (entry != null && !entry.isDirectory()) {
                    JarEntryMatch match = metadataEntry(jar, entry);
                    if (match != null) {
                        metadata.add(match);
                    }
                }
            }
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                if (entriesScanned >= MAX_JAR_ENTRIES_SCANNED || matches.size() >= MAX_JAR_MATCHES) {
                    truncated = true;
                    break;
                }
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || MOD_METADATA_ENTRIES.contains(entry.getName())) {
                    continue;
                }
                entriesScanned++;
                if (needle == null || !isInspectableJarEntry(entry)) {
                    continue;
                }
                JarEntryMatch match = matchingJarEntry(jar, entry, needle);
                if (match != null) {
                    matches.add(match);
                }
            }
        }
        return new ModJarInspection(true, null, relativeName(root, jarPath), Files.size(jarPath), needle,
                entriesScanned, truncated, List.copyOf(metadata), List.copyOf(matches));
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

    private static String normalizeQuery(String query, String name) throws IOException {
        if (query == null || query.isBlank() || query.length() > MAX_MODPACK_QUERY_LENGTH) {
            throw new IOException(name + " must be a non-empty string of at most " + MAX_MODPACK_QUERY_LENGTH + " characters.");
        }
        return query.toLowerCase(Locale.ROOT);
    }

    private static String normalizeScope(String requestedScope) throws IOException {
        String scope = requestedScope == null || requestedScope.isBlank() ? "all" : requestedScope.toLowerCase(Locale.ROOT);
        if (!Set.of("all", "recipes", "kubejs", "quests", "config").contains(scope)) {
            throw new IOException("scope must be one of all, recipes, kubejs, quests, or config.");
        }
        return scope;
    }

    private List<Path> modpackDirectories(Path root, String scope) throws IOException {
        List<String> requestedDirectories = switch (scope) {
            case "recipes" -> List.of("kubejs", "datapacks", "defaultconfigs", "global_packs", "openloader");
            case "kubejs" -> List.of("kubejs");
            case "quests" -> List.of("kubejs", "config/ftbquests", "defaultconfigs/ftbquests", "ftbquests");
            case "config" -> List.of("config", "defaultconfigs", "global_packs", "openloader");
            default -> List.of("kubejs", "datapacks", "config", "defaultconfigs", "global_packs", "openloader", "ftbquests");
        };
        LinkedHashSet<Path> directories = new LinkedHashSet<>();
        for (String requestedDirectory : requestedDirectories) {
            Path directory = resolveIfDirectory(root, requestedDirectory);
            if (directory != null) {
                directories.add(directory);
            }
        }
        if (scope.equals("all") || scope.equals("recipes")) {
            Path saves = resolveIfDirectory(root, "saves");
            if (saves != null) {
                try (Stream<Path> worlds = Files.list(saves)) {
                    for (Path world : worlds.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                        Path datapacks = resolveIfDirectory(root, relativeName(root, world) + "/datapacks");
                        if (datapacks != null) {
                            directories.add(datapacks);
                        }
                    }
                }
            }
        }
        return List.copyOf(directories);
    }

    private static boolean isSearchableTextFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            return false;
        }
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return MODPACK_TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static void addTextMatches(Path root, Path file, String needle, List<TextMatch> matches) throws IOException {
        String content = new String(readRange(file, 0, MAX_MODPACK_SEARCH_FILE_BYTES), StandardCharsets.UTF_8);
        String[] lines = content.split("\\R", -1);
        for (int index = 0; index < lines.length && matches.size() < MAX_MODPACK_SEARCH_MATCHES; index++) {
            String line = lines[index];
            int location = line.toLowerCase(Locale.ROOT).indexOf(needle);
            if (location >= 0) {
                matches.add(new TextMatch(relativeName(root, file), index + 1, snippet(line, location, needle.length())));
            }
        }
    }

    private static void validateJarName(String jarName) throws IOException {
        if (jarName == null || jarName.isBlank() || jarName.length() > MAX_MOD_JAR_NAME_LENGTH
                || jarName.indexOf('/') >= 0 || jarName.indexOf('\\') >= 0
                || !jarName.toLowerCase(Locale.ROOT).endsWith(".jar")) {
            throw new IOException("jar_name must be a direct .jar filename from minegpt_list_installed_mods.");
        }
    }

    private static JarEntryMatch metadataEntry(JarFile jar, JarEntry entry) throws IOException {
        byte[] contents = readJarEntry(jar, entry);
        if (contents == null) {
            return new JarEntryMatch(entry.getName(), "metadata", entry.getSize(), "Metadata entry is larger than the inspection limit.");
        }
        return new JarEntryMatch(entry.getName(), "metadata", entry.getSize(), snippet(new String(contents, StandardCharsets.UTF_8), 0, 0));
    }

    private static boolean isInspectableJarEntry(JarEntry entry) {
        long size = entry.getSize();
        if (size < 0 || size > MAX_JAR_ENTRY_BYTES || entry.getCompressedSize() > MAX_JAR_ENTRY_BYTES) {
            return false;
        }
        String name = entry.getName().toLowerCase(Locale.ROOT);
        return name.endsWith(".class") || JAR_TEXT_EXTENSIONS.stream().anyMatch(name::endsWith);
    }

    private static JarEntryMatch matchingJarEntry(JarFile jar, JarEntry entry, String needle) throws IOException {
        String entryName = entry.getName();
        if (entryName.toLowerCase(Locale.ROOT).contains(needle)) {
            return new JarEntryMatch(entryName, "resource-path", entry.getSize(), "Matched entry path: " + entryName);
        }
        byte[] contents = readJarEntry(jar, entry);
        if (contents == null) {
            return null;
        }
        if (entryName.toLowerCase(Locale.ROOT).endsWith(".class")) {
            String classStrings = printableStrings(contents);
            int location = classStrings.toLowerCase(Locale.ROOT).indexOf(needle);
            return location < 0 ? null : new JarEntryMatch(entryName, "class-string", entry.getSize(),
                    snippet(classStrings, location, needle.length()));
        }
        String text = new String(contents, StandardCharsets.UTF_8);
        int location = text.toLowerCase(Locale.ROOT).indexOf(needle);
        return location < 0 ? null : new JarEntryMatch(entryName, "resource", entry.getSize(),
                snippet(text, location, needle.length()));
    }

    private static byte[] readJarEntry(JarFile jar, JarEntry entry) throws IOException {
        if (entry.getSize() < 0 || entry.getSize() > MAX_JAR_ENTRY_BYTES || entry.getCompressedSize() > MAX_JAR_ENTRY_BYTES) {
            return null;
        }
        try (java.io.InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_JAR_ENTRY_BYTES + 1);
            return bytes.length > MAX_JAR_ENTRY_BYTES ? null : bytes;
        }
    }

    private static String printableStrings(byte[] contents) {
        StringBuilder result = new StringBuilder(contents.length);
        StringBuilder current = new StringBuilder();
        for (byte value : contents) {
            int character = Byte.toUnsignedInt(value);
            if (character >= 32 && character <= 126) {
                current.append((char) character);
            } else {
                appendPrintableString(result, current);
            }
        }
        appendPrintableString(result, current);
        return result.toString();
    }

    private static void appendPrintableString(StringBuilder destination, StringBuilder value) {
        if (value.length() >= 4) {
            if (!destination.isEmpty()) {
                destination.append('\n');
            }
            destination.append(value);
        }
        value.setLength(0);
    }

    private static String snippet(String content, int matchOffset, int matchLength) {
        String normalized = content.replaceAll("[\\r\\n\\t]+", " ").trim();
        if (normalized.length() <= MAX_SNIPPET_CHARACTERS) {
            return normalized;
        }
        int offset = Math.max(0, Math.min(matchOffset, normalized.length() - 1));
        int start = Math.max(0, offset - MAX_SNIPPET_CHARACTERS / 3);
        int end = Math.min(normalized.length(), start + MAX_SNIPPET_CHARACTERS - 6);
        if (end - start < MAX_SNIPPET_CHARACTERS - 6) {
            start = Math.max(0, end - (MAX_SNIPPET_CHARACTERS - 6));
        }
        return (start > 0 ? "..." : "") + normalized.substring(start, end) + (end < normalized.length() ? "..." : "");
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

    record ModpackSearchResult(String scope, String query, List<String> searchedDirectories, int searchedFiles,
                               int skippedLargeFiles, boolean truncated, List<TextMatch> matches) {
    }

    record TextMatch(String path, int line, String snippet) {
    }

    record ModJarInspection(boolean available, String detail, String jar, long sizeBytes, String query,
                            int entriesScanned, boolean truncated, List<JarEntryMatch> metadata,
                            List<JarEntryMatch> matches) {
        static ModJarInspection unavailable(String detail) {
            return new ModJarInspection(false, detail, null, 0L, null, 0, false, List.of(), List.of());
        }
    }

    record JarEntryMatch(String path, String kind, long sizeBytes, String snippet) {
    }
}
