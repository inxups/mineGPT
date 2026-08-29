package com.inxups.minegpt.bridge;

import com.inxups.minegpt.shared.GitHubSkillDownload;
import java.io.IOException;
import java.nio.file.Path;

/** Imports one explicitly requested public GitHub Markdown skill into the active instance. */
final class GitHubSkillImporter {
    private final SkillStore skills;

    GitHubSkillImporter(SkillStore skills) {
        this.skills = skills;
    }

    ImportResult importSkill(String repository, String sourcePath, String ref,
                             String destinationPath, boolean overwrite) throws IOException, InterruptedException {
        String destination = destinationPath == null || destinationPath.isBlank()
                ? GitHubSkillDownload.defaultInstallPath(repository, sourcePath) : destinationPath;
        skills.validateImportTarget(destination);
        GitHubSkillDownload.DownloadedSkill downloaded = GitHubSkillDownload.download(repository, sourcePath, ref);
        Path installed = skills.writeImportedSkill(destination, downloaded.contents(), overwrite);
        return new ImportResult(downloaded.repository(), downloaded.ref(), downloaded.sourceUrl(),
                skills.directory().relativize(installed).toString().replace('\\', '/'), downloaded.contents().length, overwrite);
    }

    record ImportResult(String repository, String ref, String sourceUrl, String installedPath,
                        int bytesWritten, boolean overwritten) {
    }
}
