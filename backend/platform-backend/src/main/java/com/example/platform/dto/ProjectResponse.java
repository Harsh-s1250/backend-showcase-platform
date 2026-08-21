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
        Instant createdAt
) {
    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getGithubRepoUrl(),
                project.getBranch(),
                project.getStatus(),
                project.getCreatedAt()
        );
    }
}