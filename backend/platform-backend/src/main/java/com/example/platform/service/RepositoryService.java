package com.example.platform.service;

import com.example.platform.exception.RepositoryCloneException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class RepositoryService {

    private static final Path WORKSPACE_ROOT = Path.of("workspace");
    private static final int CLONE_TIMEOUT_SECONDS = 120;

    public String cloneRepository(UUID projectId, String githubRepoUrl, String branch) {
        validateGithubUrl(githubRepoUrl);

        Path targetDir = WORKSPACE_ROOT.resolve(projectId.toString());

        try {
            // If this project was cloned before, remove the old directory first —
            // git clone refuses to target a non-empty directory.
            if (Files.exists(targetDir)) {
                deleteRecursively(targetDir);
            }

            Files.createDirectories(WORKSPACE_ROOT);

            List<String> command = List.of(
                    "git", "clone",
                    "--branch", branch,
                    "--single-branch",
                    "--depth", "1",
                    githubRepoUrl,
                    targetDir.toString()
            );

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.redirectErrorStream(true);

            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes());

            boolean finished = process.waitFor(CLONE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RepositoryCloneException("Clone timed out after " + CLONE_TIMEOUT_SECONDS + "s");
            }

            if (process.exitValue() != 0) {
                throw new RepositoryCloneException("git clone failed: " + output);
            }

            return targetDir.toAbsolutePath().toString();

        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RepositoryCloneException("Failed to clone repository", e);
        }
    }

    public void deleteWorkspace(java.util.UUID projectId) {
        Path targetDir = WORKSPACE_ROOT.resolve(projectId.toString());
        try {
            if (Files.exists(targetDir)) {
                deleteRecursively(targetDir);
            }
        } catch (IOException e) {
            // Best-effort — same reasoning as database cleanup: don't block project deletion.
            System.err.println("Warning: failed to delete workspace for " + projectId + ": " + e.getMessage());
        }
    }

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var entries = Files.list(path)) {
                for (Path entry : entries.toList()) {
                    deleteRecursively(entry);
                }
            }
        }
        // Git marks files under .git/objects as read-only on Windows;
        // clear that flag before attempting delete, or it throws AccessDeniedException.
        path.toFile().setWritable(true);
        Files.delete(path);
    }

    private void validateGithubUrl(String url) {
        if (url == null) {
            throw new RepositoryCloneException("Repository URL is required");
        }
        // Strict pattern: https://github.com/{owner}/{repo}, owner/repo limited to
        // characters GitHub actually allows — rejects path traversal, embedded
        // credentials, unexpected hosts, and other malformed input up front.
        if (!url.matches("^https://github\\.com/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+/?$")) {
            throw new RepositoryCloneException("Invalid GitHub repository URL format");
        }
    }
}