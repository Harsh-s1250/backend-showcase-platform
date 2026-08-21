package com.example.platform.service;

import com.example.platform.build.DockerfileGenerator;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.BuildImageResultCallback;
import com.github.dockerjava.api.model.BuildResponseItem;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.example.platform.entity.DeploymentLog;
import com.example.platform.repository.DeploymentLogRepository;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

@Service
public class BuildService {

    private static final Duration BUILD_TIMEOUT = Duration.ofMinutes(10);

    private final DockerClient dockerClient;
    private final DeploymentLogRepository deploymentLogRepository;

    public BuildService(DeploymentLogRepository deploymentLogRepository) {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://localhost:2375")
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
        this.deploymentLogRepository = deploymentLogRepository;
    }

    public SseEmitter buildProjectStreaming(UUID projectId, String clonePath, String javaVersion,
                                            java.util.function.Consumer<String> onSuccess) {
        SseEmitter emitter = new SseEmitter(BUILD_TIMEOUT.toMillis());
        StringBuilder capturedOutput = new StringBuilder();

        Executors.newSingleThreadExecutor().submit(() -> {
            try {
                Path repoPath = Path.of(clonePath);
                Path dockerfilePath = repoPath.resolve("Dockerfile");
                Files.writeString(dockerfilePath, DockerfileGenerator.generate(javaVersion));

                String imageTag = "showcase-project-" + projectId;

                emitter.send(SseEmitter.event().name("status").data("Starting build..."));

                BuildImageResultCallback callback = new BuildImageResultCallback() {
                    @Override
                    public void onNext(BuildResponseItem item) {
                        String stream = item.getStream();
                        if (stream != null && !stream.isBlank()) {
                            capturedOutput.append(stream.trim()).append("\n");
                            try {
                                emitter.send(SseEmitter.event().name("log").data(stream.trim()));
                            } catch (IOException e) {
                                this.onError(e);
                            }
                        }
                        super.onNext(item);
                    }
                };

                String imageId = dockerClient.buildImageCmd(repoPath.toFile())
                        .withTags(java.util.Set.of(imageTag))
                        .exec(callback)
                        .awaitImageId();

                Files.deleteIfExists(dockerfilePath);

                deploymentLogRepository.save(
                        new DeploymentLog(projectId, "BUILD", capturedOutput.toString(), true));

                onSuccess.accept(imageId);

                emitter.send(SseEmitter.event().name("complete").data(imageId));
                emitter.complete();

            } catch (Exception e) {
                capturedOutput.append("ERROR: ").append(e.getMessage()).append("\n");
                deploymentLogRepository.save(
                        new DeploymentLog(projectId, "BUILD", capturedOutput.toString(), false));

                try {
                    emitter.send(SseEmitter.event().name("error").data(e.getMessage()));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}