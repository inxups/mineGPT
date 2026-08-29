package com.inxups.minegpt.shared;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/** Downloads one bounded, public GitHub Markdown skill from GitHub's raw-content host. */
public final class GitHubSkillDownload {
    public static final int MAX_SKILL_BYTES = 256 * 1024;
    private static final Pattern REPOSITORY_PART = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern SOURCE_PATH_PART = Pattern.compile("[A-Za-z0-9._@+-]{1,128}");
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private GitHubSkillDownload() {
    }

    public static DownloadedSkill download(String repository, String sourcePath, String ref)
            throws IOException, InterruptedException {
        URI source = rawGithubUri(repository, sourcePath, ref);
        HttpRequest request = HttpRequest.newBuilder(source)
                .GET()
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "text/markdown, text/plain;q=0.9, */*;q=0.1")
                .header("User-Agent", "MineGPT-Bridge")
                .build();
        HttpResponse<InputStream> response;
        try {
            response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (Exception exception) {
            throw new IOException("Could not download the GitHub skill: " + exception.getMessage(), exception);
        }
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("GitHub returned HTTP " + response.statusCode() + " for " + source + ".");
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > MAX_SKILL_BYTES) {
                throw new IOException("GitHub skill exceeds " + MAX_SKILL_BYTES + " bytes.");
            }
            byte[] contents = input.readNBytes(MAX_SKILL_BYTES + 1);
            if (contents.length == 0 || contents.length > MAX_SKILL_BYTES) {
                throw new IOException("GitHub skill must contain at most " + MAX_SKILL_BYTES + " bytes.");
            }
            requireUtf8(contents);
            return new DownloadedSkill(repository, normalizedRef(ref), source.toString(), contents);
        }
    }

    /** Downloads a skill from a copied GitHub file-page or raw-content HTTPS URL. */
    public static DownloadedSkill downloadUrl(String githubUrl) throws IOException, InterruptedException {
        GitHubSource source = parseGithubUrl(githubUrl);
        return download(source.repository(), source.sourcePath(), source.ref());
    }

    /** Validates a copied GitHub URL and returns the fixed raw-content URL without downloading it. */
    public static URI rawGithubUriFromUrl(String githubUrl) throws IOException {
        GitHubSource source = parseGithubUrl(githubUrl);
        return rawGithubUri(source.repository(), source.sourcePath(), source.ref());
    }

    /** Returns the default local skill path: source-folder/source-filename.md. */
    public static String defaultInstallPath(String repository, String sourcePath) throws IOException {
        rawGithubUri(repository, sourcePath, null);
        String repositoryName = repository.substring(repository.indexOf('/') + 1);
        Path source = Path.of(sourcePath);
        String filename = source.getFileName().toString();
        Path parent = source.getParent();
        String folder = parent == null ? repositoryName : parent.getFileName().toString();
        return folder + "/" + filename;
    }

    /** Returns the default local skill path for a copied GitHub URL without downloading it. */
    public static String defaultInstallPathFromUrl(String githubUrl) throws IOException {
        GitHubSource source = parseGithubUrl(githubUrl);
        return defaultInstallPath(source.repository(), source.sourcePath());
    }

    public static URI rawGithubUri(String repository, String sourcePath, String ref) throws IOException {
        String[] repositoryParts = repository == null ? new String[0] : repository.split("/", -1);
        if (repositoryParts.length != 2 || !REPOSITORY_PART.matcher(repositoryParts[0]).matches()
                || !REPOSITORY_PART.matcher(repositoryParts[1]).matches()) {
            throw new IOException("repository must be a public GitHub owner/repository name.");
        }
        String resolvedRef = normalizedRef(ref);
        if (!REF.matcher(resolvedRef).matches()) {
            throw new IOException("ref must be a simple Git branch or tag name.");
        }
        validateSourcePath(sourcePath);
        return URI.create("https://raw.githubusercontent.com/" + repository + "/" + resolvedRef + "/" + sourcePath);
    }

    private static GitHubSource parseGithubUrl(String githubUrl) throws IOException {
        if (githubUrl == null || githubUrl.isBlank()) {
            throw new IOException("github_url is required.");
        }
        URI uri;
        try {
            uri = new URI(githubUrl);
        } catch (URISyntaxException exception) {
            throw new IOException("github_url must be a valid HTTPS GitHub URL.", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getHost() == null) {
            throw new IOException("github_url must be a plain HTTPS GitHub file URL without a query or fragment.");
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String[] parts = uri.getRawPath().split("/");
        if (host.equals("raw.githubusercontent.com")) {
            if (parts.length < 5 || parts[1].isEmpty() || parts[2].isEmpty()) {
                throw new IOException("Raw GitHub URLs must name an owner, repository, ref, and Markdown file.");
            }
            return githubSource(parts, 1, 3, 4);
        }
        if (host.equals("github.com")) {
            if (parts.length < 6 || parts[1].isEmpty() || parts[2].isEmpty() || !parts[3].equals("blob")) {
                throw new IOException("GitHub URLs must use https://github.com/owner/repository/blob/ref/path.md.");
            }
            return githubSource(parts, 1, 4, 5);
        }
        throw new IOException("github_url must use github.com or raw.githubusercontent.com.");
    }

    private static GitHubSource githubSource(String[] parts, int ownerIndex, int refIndex, int sourceStartIndex) throws IOException {
        String repository = parts[ownerIndex] + "/" + parts[ownerIndex + 1];
        String ref = parts[refIndex];
        String sourcePath = String.join("/", java.util.Arrays.copyOfRange(parts, sourceStartIndex, parts.length));
        rawGithubUri(repository, sourcePath, ref);
        return new GitHubSource(repository, sourcePath, ref);
    }

    private static String normalizedRef(String ref) {
        return ref == null || ref.isBlank() ? "main" : ref;
    }

    private static void validateSourcePath(String sourcePath) throws IOException {
        if (sourcePath == null || sourcePath.isBlank() || sourcePath.length() > 512
                || sourcePath.indexOf('\\') >= 0 || sourcePath.startsWith("/") || sourcePath.endsWith("/")) {
            throw new IOException("source_path must be a relative Markdown path in the GitHub repository.");
        }
        String[] parts = sourcePath.split("/");
        if (parts.length > 9 || !sourcePath.toLowerCase(Locale.ROOT).endsWith(".md")) {
            throw new IOException("source_path must be a Markdown path no deeper than eight directories.");
        }
        for (String part : parts) {
            if (!SOURCE_PATH_PART.matcher(part).matches() || part.equals(".") || part.equals("..")) {
                throw new IOException("source_path contains an invalid path segment.");
            }
        }
    }

    private static void requireUtf8(byte[] contents) throws IOException {
        try {
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contents));
        } catch (CharacterCodingException exception) {
            throw new IOException("GitHub skills must be valid UTF-8 Markdown.", exception);
        }
    }

    public record DownloadedSkill(String repository, String ref, String sourceUrl, byte[] contents) {
        public DownloadedSkill {
            contents = contents.clone();
        }

        @Override
        public byte[] contents() {
            return contents.clone();
        }
    }

    private record GitHubSource(String repository, String sourcePath, String ref) {
    }
}
