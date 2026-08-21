package com.example.platform.dto;

import jakarta.validation.constraints.NotBlank;

public record CreateProjectRequest(
        @NotBlank String name,
        @NotBlank String githubRepoUrl,
        String branch
) {}