package com.example.platform.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.UUID;

@Service
public class RunService {

    private static final long MEMORY_LIMIT_BYTES = 256L * 1024 * 1024; // 256MB, same as Milestone 1
    private static final long CPU_QUOTA = 50000; // 0.5 CPU, same as Milestone 1
    private static final int HEALTH_CHECK_ATTEMPTS = 60;
    private static final int HEALTH_CHECK_DELAY_MS = 2000;

    private final DockerClient dockerClient;

    public RunService() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://localhost:2375")
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        this.dockerClient = DockerClientImpl.getInstance(config, httpClient);
    }

    public record RunResult(String containerId, int hostPort, boolean healthy) {}

    public RunResult runContainer(UUID projectId, String imageId, DatabaseProvisionerService.DbCredentials dbCredentials) {
        String containerName = "showcase-run-" + projectId;

        try {
            dockerClient.removeContainerCmd(containerName).withForce(true).exec();
        } catch (Exception ignored) {}

        int hostPort = findFreePort();

        ExposedPort containerPort = ExposedPort.tcp(8080);
        Ports portBindings = new Ports();
        portBindings.bind(containerPort, Ports.Binding.bindIpAndPort("127.0.0.1", hostPort));

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withPortBindings(portBindings)
                .withMemory(MEMORY_LIMIT_BYTES)
                .withCpuQuota(CPU_QUOTA)
                .withCpuPeriod(100000L);

        var containerCmd = dockerClient.createContainerCmd(imageId)
                .withName(containerName)
                .withExposedPorts(containerPort)
                .withHostConfig(hostConfig);

        if (dbCredentials != null) {
            // host.docker.internal lets the container reach the host machine's Postgres instance —
            // Docker Desktop provides this DNS name specifically for this purpose.
            containerCmd.withEnv(List.of(
                    "SPRING_DATASOURCE_URL=jdbc:postgresql://host.docker.internal:5432/" + dbCredentials.dbName(),
                    "SPRING_DATASOURCE_USERNAME=" + dbCredentials.username(),
                    "SPRING_DATASOURCE_PASSWORD=" + dbCredentials.password()
            ));
        }

        CreateContainerResponse container = containerCmd.exec();
        dockerClient.startContainerCmd(container.getId()).exec();

        boolean healthy = waitForHealthy(hostPort);

        return new RunResult(container.getId(), hostPort, healthy);
    }

    private int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Could not find a free port", e);
        }
    }

    private boolean waitForHealthy(int port) {
        for (int i = 0; i < HEALTH_CHECK_ATTEMPTS; i++) {
            if (isApplicationReady(port)) {
                return true;
            }
            try {
                Thread.sleep(HEALTH_CHECK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isApplicationReady(int port) {
        // Prefer a real health endpoint if the project exposes one (our own sample
        // project convention, and a common Spring Boot practice more broadly).
        if (respondsWithHttpStatus("http://localhost:" + port + "/api/health")) {
            return true;
        }
        // Fall back to root — any real HTTP response (even a 404) proves the
        // application's request-handling pipeline is actually up, not just a
        // raw TCP listener. This is strictly more honest than a socket check alone.
        return respondsWithHttpStatus("http://localhost:" + port + "/");
    }

    private boolean respondsWithHttpStatus(String url) {
        try {
            java.net.URL target = java.net.URI.create(url).toURL();
            java.net.HttpURLConnection connection = (java.net.HttpURLConnection) target.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            int status = connection.getResponseCode();
            connection.disconnect();
            // Any status code at all (200, 404, 500...) means the HTTP layer itself
            // is alive and responding — that's the actual signal we care about.
            return status > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public void stopContainer(String containerId) {
        if (containerId == null) return;
        try {
            dockerClient.stopContainerCmd(containerId).exec();
        } catch (Exception e) {
            // Container may already be stopped or removed — not a failure worth surfacing.
        }
    }

    public void removeContainer(String containerId) {
        if (containerId == null) return;
        try {
            dockerClient.removeContainerCmd(containerId).withForce(true).exec();
        } catch (Exception e) {
            // Already gone — fine.
        }
    }

    public RunResult restartContainer(UUID projectId, String containerId) {
        dockerClient.startContainerCmd(containerId).exec();

        // We don't know the host port here without querying Docker for it —
        // inspect the container to read back its actual port binding.
        var inspection = dockerClient.inspectContainerCmd(containerId).exec();
        int hostPort = Integer.parseInt(
                inspection.getNetworkSettings().getPorts()
                        .getBindings().values().iterator().next()[0].getHostPortSpec()
        );

        boolean healthy = waitForHealthy(hostPort);
        return new RunResult(containerId, hostPort, healthy);
    }
}