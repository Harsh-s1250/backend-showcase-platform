package com.example.platform.dto;

import com.example.platform.entity.Project;
import java.time.Instant;
import java.util.UUID;

public record ProjectResponse(
        UUID id,
        String name,
        String githubRepoUrl,
        String branch,
        String status,
        Instant createdAt,
        String projectType,
        String projectTypeStatus,
        String projectTypeReason,
        boolean cloned,
        boolean built,
        Integer hostPort
) {
    // cloned/built are derived booleans (not the raw clonePath/dockerImageId) so the dashboard
    // can compute which action button to show next without the response leaking internal
    // Docker image IDs or filesystem paths — same "don't expose more than the UI needs"
    // instinct as the rest of this DTO.
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getGithubRepoUrl(),
                project.getBranch(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getProjectType(),
                project.getProjectTypeStatus(),
                project.getProjectTypeReason(),
                project.getClonePath() != null,
                project.getDockerImageId() != null,
                project.getHostPort()
        );
    }
}