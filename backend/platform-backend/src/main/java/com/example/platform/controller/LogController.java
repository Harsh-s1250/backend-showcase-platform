package com.example.platform.controller;

import com.example.platform.entity.DeploymentLog;
import com.example.platform.entity.Project;
import com.example.platform.entity.User;
import com.example.platform.exception.ProjectNotFoundException;
import com.example.platform.repository.DeploymentLogRepository;
import com.example.platform.repository.ProjectRepository;
import com.example.platform.service.CurrentUserService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.LogContainerCmd;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/projects")
public class LogController {

    private final ProjectRepository projectRepository;
    private final DeploymentLogRepository deploymentLogRepository;
    private final CurrentUserService currentUserService;
    private final DockerClient dockerClient;

    public LogController(ProjectRepository projectRepository,
                         DeploymentLogRepository deploymentLogRepository,
                         CurrentUserService currentUserService) {
        this.projectRepository = projectRepository;
        this.deploymentLogRepository = deploymentLogRepository;
        this.currentUserService = currentUserService;

        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://localhost:2375")
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    @GetMapping("/{id}/logs/build")
    public List<DeploymentLog> buildLogs(@PathVariable UUID id, HttpSession session) {
        Project project = requireOwnedProject(id, session);
        return deploymentLogRepository.findByProjectIdOrderByCreatedAtDesc(project.getId());
    }

    @GetMapping("/{id}/logs/application")
    public String applicationLogs(@PathVariable UUID id, HttpSession session) {
        Project project = requireOwnedProject(id, session);

        if (project.getContainerId() == null) {
            return "No container has been started for this project yet.";
        }

        StringBuilder output = new StringBuilder();
        try {
            dockerClient.logContainerCmd(project.getContainerId())
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(200) // last 200 lines — a snapshot, not the full history
                    .exec(new ResultCallback.Adapter<Frame>() {
                        @Override
                        public void onNext(Frame frame) {
                            output.append(new String(frame.getPayload()));
                        }
                    })
                    .awaitCompletion(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "Could not fetch application logs: " + e.getMessage();
        }

        return output.toString();
    }

    private Project requireOwnedProject(UUID id, HttpSession session) {
        Project project = projectRepository.findById(id)
                .orElseThrow(() -> new ProjectNotFoundException("Project not found: " + id));
        User currentUser = currentUserService.requireCurrentUser(session);
        currentUserService.requireOwnership(currentUser, project.getOwner() != null ? project.getOwner().getId() : null);
        return project;
    }
}