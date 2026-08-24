package com.example.platform.service;

import com.example.platform.analyzer.DetectionStatus;
import com.example.platform.analyzer.InterfaceType;
import com.example.platform.analyzer.ProjectType;
import com.example.platform.entity.Project;
import org.springframework.stereotype.Service;

/**
 * Decides which generated interface (if any) a project should get, per PRD §18
 * "Interface Selection" and §21 "Interface Fallback".
 *
 * Deliberately conservative: a ProjectType classification alone does not mean the
 * platform can actually *deliver* that experience yet. GENERATED_REST_UI (PRD Phase D/E)
 * and BROWSER_TERMINAL (PRD Phase F/G) are not implemented in this build, so this service
 * reports {@code interfaceAvailable = false} for them rather than promising something the
 * platform can't do — "never generate a misleading interface simply because the platform
 * can technically generate something" (PRD §21).
 */
@Service
public class ExperienceService {

    public record ExperienceResult(
            ProjectType projectType,
            DetectionStatus projectTypeStatus,
            String projectTypeReason,
            InterfaceType interfaceType,
            boolean interfaceAvailable,
            String status,
            String deploymentStatus,
            boolean isRunning
    ) {}

    public ExperienceResult resolve(Project project) {
        boolean isRunning = "RUNNING".equals(project.getStatus());

        if (project.getProjectType() == null) {
            return new ExperienceResult(
                    null, null, null,
                    InterfaceType.NONE, false,
                    "NOT_ANALYZED", project.getStatus(), isRunning
            );
        }

        ProjectType projectType = project.getProjectTypeEnum();
        DetectionStatus detectionStatus = DetectionStatus.valueOf(project.getProjectTypeStatus());
        String reason = project.getProjectTypeReason();

        InterfaceType interfaceType;
        boolean interfaceAvailable;

        switch (projectType) {
            case REST_APPLICATION -> {
                // GENERATED_REST_UI (dynamic OpenAPI -> CRUD UI) is a later phase.
                // API Explorer already exists and always works for a REST project, so it's
                // the honest, working fallback today rather than a half-built custom UI.
                interfaceType = InterfaceType.API_EXPLORER;
                interfaceAvailable = true;
            }
            case CONSOLE_APPLICATION -> {
                // PRD Phase F/G: the interactive stdin/stdout runtime and WebSocket
                // terminal are implemented — see ConsoleAttachSession/TerminalWebSocketHandler.
                interfaceType = InterfaceType.BROWSER_TERMINAL;
                interfaceAvailable = true;
            }
            case UNSUPPORTED, UNKNOWN -> {
                interfaceType = InterfaceType.NONE;
                interfaceAvailable = false;
            }
            default -> {
                interfaceType = InterfaceType.NONE;
                interfaceAvailable = false;
            }
        }

        String status;
        if (!isRunning) {
            status = "NOT_DEPLOYED";
        } else if (interfaceAvailable) {
            status = "READY";
        } else {
            status = "DEPLOYED_NO_INTERFACE";
        }

        return new ExperienceResult(
                projectType, detectionStatus, reason,
                interfaceType, interfaceAvailable,
                status, project.getStatus(), isRunning
        );
    }
}
