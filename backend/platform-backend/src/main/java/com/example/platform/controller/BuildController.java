package com.example.platform.controller;

import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import com.example.platform.service.BuildService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.platform.entity.User;
import com.example.platform.service.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import com.example.platform.exception.ProjectNotFoundException;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class BuildController {

    private final ProjectRepository projectRepository;
    private final BuildService buildService;
    private final CurrentUserService currentUserService;

    public BuildController(ProjectRepository projectRepository, BuildService buildService,
                           CurrentUserService currentUserService) {
        this.projectRepository = projectRepository;
        this.buildService = buildService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/{id}/build")
    public SseEmitter build(@PathVariable UUID id, HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));

        User currentUser = currentUserService.requireCurrentUser(session);
        currentUserService.requireOwnership(currentUser, project.getOwner() != null ? project.getOwner().getId() : null);

        if (project.getClonePath() == null) {
            throw new IllegalStateException("Project has not been cloned yet.");
        }

        project.setStatus("BUILDING");
        projectRepository.save(project);

        boolean isConsoleApp = project.getProjectTypeEnum() == com.example.platform.analyzer.ProjectType.CONSOLE_APPLICATION;
        boolean isSpringBoot = "Spring Boot".equals(project.getDetectedFramework());

        return buildService.buildProjectStreaming(
                id,
                project.getClonePath(),
                project.getDetectedJavaVersion(),
                project.getDetectedBuildTool(),
                project.getMainClass(),
                isConsoleApp,
                isSpringBoot,
                project.getDetectedDatabaseDriver(),
                imageId -> {
                    project.setDockerImageId(imageId);
                    project.setStatus("BUILT");
                    projectRepository.save(project);
                }
        );
    }
}