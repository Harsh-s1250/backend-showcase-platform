package com.example.platform.controller;

import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import com.example.platform.service.RestUiSchemaService;
import com.example.platform.service.UiSchema;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class UiSchemaController {

    private final ProjectRepository projectRepository;
    private final RestUiSchemaService restUiSchemaService;

    public UiSchemaController(ProjectRepository projectRepository, RestUiSchemaService restUiSchemaService) {
        this.projectRepository = projectRepository;
        this.restUiSchemaService = restUiSchemaService;
    }

    // Public, same reasoning as /showcase and /experience — this is what decides whether
    // the showcase page can render a generated CRUD UI or should stick to API Explorer.
    @GetMapping("/{id}/ui-schema")
    public UiSchema.Result uiSchema(@PathVariable UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        if (project.getHostPort() == null) {
            return UiSchema.Result.unsupported("Project is not currently running.");
        }

        return restUiSchemaService.fetchAndBuild(project.getHostPort());
    }
}
