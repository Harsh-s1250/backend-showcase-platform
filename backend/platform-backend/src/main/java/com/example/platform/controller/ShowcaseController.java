package com.example.platform.controller;

import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ShowcaseController {

    private final ProjectRepository projectRepository;

    public ShowcaseController(ProjectRepository projectRepository) {
        this.projectRepository = projectRepository;
    }

    // Deliberately public — this is what a stranger with the link sees.
    // Only hand-picked, safe-to-share fields are returned here.
    @GetMapping("/{id}/showcase")
    public Map<String, Object> showcase(@PathVariable UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        boolean isRunning = "RUNNING".equals(project.getStatus());

        return Map.of(
                "id", project.getId(),
                "name", project.getName(),
                "githubRepoUrl", project.getGithubRepoUrl(),
                "branch", project.getBranch(),
                "status", project.getStatus(),
                "isRunning", isRunning,
                "buildTool", project.getDetectedBuildTool() != null ? project.getDetectedBuildTool() : "Unknown",
                "javaVersion", project.getDetectedJavaVersion() != null ? project.getDetectedJavaVersion() : "Unknown"
        );
    }
}