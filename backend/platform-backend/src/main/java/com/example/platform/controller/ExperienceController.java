package com.example.platform.controller;

import com.example.platform.dto.ExperienceResponse;
import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import com.example.platform.service.ExperienceService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects")
public class ExperienceController {

    private final ProjectRepository projectRepository;
    private final ExperienceService experienceService;

    public ExperienceController(ProjectRepository projectRepository, ExperienceService experienceService) {
        this.projectRepository = projectRepository;
        this.experienceService = experienceService;
    }

    // Deliberately public, same reasoning as ShowcaseController — this is what tells the
    // showcase frontend (owner or stranger with the link) which experience to render.
    @GetMapping("/{id}/experience")
    public ExperienceResponse experience(@PathVariable UUID id) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found"));

        return ExperienceResponse.from(experienceService.resolve(project));
    }
}
