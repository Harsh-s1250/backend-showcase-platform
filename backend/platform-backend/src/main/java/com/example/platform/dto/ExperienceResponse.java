package com.example.platform.dto;

import com.example.platform.service.ExperienceService.ExperienceResult;

public record ExperienceResponse(
        String projectType,
        String projectTypeStatus,
        String projectTypeReason,
        String interfaceType,
        boolean interfaceAvailable,
        String status,
        String deploymentStatus,
        boolean isRunning
) {
    public static ExperienceResponse from(ExperienceResult result) {
        return new ExperienceResponse(
                result.projectType() != null ? result.projectType().name() : null,
                result.projectTypeStatus() != null ? result.projectTypeStatus().name() : null,
                result.projectTypeReason(),
                result.interfaceType().name(),
                result.interfaceAvailable(),
                result.status(),
                result.deploymentStatus(),
                result.isRunning()
        );
    }
}
