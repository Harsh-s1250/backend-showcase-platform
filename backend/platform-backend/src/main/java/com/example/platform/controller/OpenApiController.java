package com.example.platform.controller;

import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import com.example.platform.entity.User;
import com.example.platform.service.CurrentUserService;
import jakarta.servlet.http.HttpSession;
import com.example.platform.exception.ProjectNotFoundException;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class OpenApiController {

    private final ProjectRepository projectRepository;
    private final RestClient restClient = RestClient.create();
    private final CurrentUserService currentUserService;

    public OpenApiController(ProjectRepository projectRepository, CurrentUserService currentUserService) {
        this.projectRepository = projectRepository;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/{id}/openapi")
    public ResponseEntity<String> getOpenApiSpec(@PathVariable UUID id, HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));

        if (project.getHostPort() == null) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body("{\"error\":\"Project is not currently running. Call /run first.\"}");
        }

        String containerUrl = "http://localhost:" + project.getHostPort() + "/v3/api-docs";

        try {
            String spec = restClient.get()
                    .uri(containerUrl)
                    .retrieve()
                    .body(String.class);

            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(spec);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body("{\"error\":\"Could not fetch OpenAPI spec. Does this project have springdoc-openapi configured?\"}");
        }
    }
}