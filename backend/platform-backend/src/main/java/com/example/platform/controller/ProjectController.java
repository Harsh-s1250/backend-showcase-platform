package com.example.platform.controller;

import com.example.platform.dto.CreateProjectRequest;
import com.example.platform.dto.ProjectResponse;
import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import com.example.platform.service.RepositoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.example.platform.analyzer.AnalysisResult;
import com.example.platform.service.AnalyzerService;
import com.example.platform.entity.User;
import com.example.platform.service.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import com.example.platform.exception.ProjectNotFoundException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectRepository projectRepository;
    private final RepositoryService repositoryService;
    private final AnalyzerService analyzerService;
    private final CurrentUserService currentUserService;

    public ProjectController(ProjectRepository projectRepository, RepositoryService repositoryService,
                             AnalyzerService analyzerService, CurrentUserService currentUserService) {
        this.projectRepository = projectRepository;
        this.repositoryService = repositoryService;
        this.analyzerService = analyzerService;
        this.currentUserService = currentUserService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectResponse create(@Valid @RequestBody CreateProjectRequest request, HttpSession session) {
        User currentUser = currentUserService.requireCurrentUser(session);

        Project project = new Project(request.name(), request.githubRepoUrl(), request.branch());
        project.setOwner(currentUser);
        Project saved = projectRepository.save(project);
        return ProjectResponse.from(saved);
    }

    @GetMapping
    public List<ProjectResponse> list(HttpSession session) {
        User currentUser = currentUserService.requireCurrentUser(session);
        return projectRepository.findAll().stream()
                .filter(p -> p.getOwner() != null && p.getOwner().getId().equals(currentUser.getId()))
                .map(ProjectResponse::from)
                .toList();
    }

    @PostMapping("/{id}/clone")
    public ProjectResponse cloneRepository(@PathVariable UUID id, HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));

        User currentUser = currentUserService.requireCurrentUser(session);
        currentUserService.requireOwnership(currentUser, project.getOwner() != null ? project.getOwner().getId() : null);

        project.setStatus("CLONING");
        projectRepository.save(project);

        try {
            String clonePath = repositoryService.cloneRepository(id, project.getGithubRepoUrl(), project.getBranch());
            project.setClonePath(clonePath);
            project.setStatus("CLONED");
        } catch (RuntimeException e) {
            project.setStatus("CLONE_FAILED");
            projectRepository.save(project);
            throw e;
        }

        Project saved = projectRepository.save(project);
        return ProjectResponse.from(saved);
    }

    @PostMapping("/{id}/analyze")
    public AnalysisResult analyzeRepository(@PathVariable UUID id, HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));

        User currentUser = currentUserService.requireCurrentUser(session);
        currentUserService.requireOwnership(currentUser, project.getOwner() != null ? project.getOwner().getId() : null);

        if (project.getClonePath() == null) {
            throw new IllegalStateException("Project has not been cloned yet. Call /clone first.");
        }

        AnalysisResult result = analyzerService.analyze(project.getClonePath());

        if (result.buildTool().value() != null) {
            project.setDetectedBuildTool(result.buildTool().value());
        }
        if (result.javaVersion().value() != null) {
            project.setDetectedJavaVersion(result.javaVersion().value());
        }
        // Always persist the project type classification, even UNKNOWN/UNSUPPORTED —
        // silently leaving stale/absent data would violate the "never guess blindly,
        // never hide a low-confidence result" philosophy the analyzer already follows.
        project.setProjectTypeDetection(result.projectType());

        // Persisted so RunController can build the correct Docker ENTRYPOINT for console apps
        // (see DockerfileGenerator) without re-running analysis at build/run time.
        project.setMainClass(result.mainClass().value());

        // Persisted so RunController can decide whether to provision a database at /run time
        // (see the conditional check there) instead of always provisioning one regardless of
        // whether the project actually uses a database.
        project.setDetectedDatabaseDriver(result.databaseDriver().value());

        // Persisted so the build step knows whether it's safe to patch a console app's jar
        // manifest with a detected main class (never safe for Spring Boot — see DockerfileGenerator).
        project.setDetectedFramework(result.framework().value());

        projectRepository.save(project);

        return result;
    }
}