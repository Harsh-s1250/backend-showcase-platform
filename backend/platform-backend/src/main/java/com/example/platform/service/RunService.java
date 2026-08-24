package com.example.platform.service;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Ports;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.UUID;

@Service
public class RunService {

    private static final long MEMORY_LIMIT_BYTES = 256L * 1024 * 1024; // 256MB, same as Milestone 1
    private static final long CPU_QUOTA = 50000; // 0.5 CPU, same as Milestone 1
    private static final int HEALTH_CHECK_ATTEMPTS = 90; // 180s — 60 attempts (120s) was too tight;
    // a real DB-backed Spring Boot app on this CPU limit was observed taking ~123s to boot.
    private static final int HEALTH_CHECK_DELAY_MS = 2000;

    // Console apps have no HTTP server at all, so an HTTP health check would always fail
    // and burn the full HEALTH_CHECK_ATTEMPTS window for nothing (PRD Phase F). "Healthy"
    // for a console app just means the container didn't immediately crash — a much shorter
    // check is enough, and a short check is honest here: we can't know a console app is
    // "ready" for input the way we can tell an HTTP server is ready.
    private static final int CONTAINER_STATE_CHECK_ATTEMPTS = 6; // ~6s
    private static final int CONTAINER_STATE_CHECK_DELAY_MS = 1000;

    private final DockerClient dockerClient;

    public RunService(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    public record RunResult(String containerId, int hostPort, boolean healthy) {}

    public RunResult runContainer(UUID projectId, String imageId,
                                    DatabaseProvisionerService.DbCredentials dbCredentials,
                                    boolean interactive) {
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

        if (interactive) {
            // Keeps the container's stdin file descriptor open indefinitely (like `docker
            // run -i`) instead of it hitting EOF the instant the process starts — without
            // this, a console app's very first Scanner.nextInt()/readLine() would throw on
            // startup, before any browser terminal ever gets a chance to attach.
            containerCmd.withStdinOpen(true).withTty(false);
        }

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

        boolean healthy = interactive ? waitForContainerRunning(container.getId()) : waitForHealthy(hostPort);

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

    private boolean waitForContainerRunning(String containerId) {
        for (int i = 0; i < CONTAINER_STATE_CHECK_ATTEMPTS; i++) {
            if (isContainerRunning(containerId)) {
                return true;
            }
            try {
                Thread.sleep(CONTAINER_STATE_CHECK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    public boolean isContainerRunning(String containerId) {
        try {
            var state = dockerClient.inspectContainerCmd(containerId).exec().getState();
            return state != null && Boolean.TRUE.equals(state.getRunning());
        } catch (Exception e) {
            return false;
        }
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

    public RunResult restartContainer(UUID projectId, String containerId, int hostPort, boolean interactive) {
        try {
            dockerClient.startContainerCmd(containerId).exec();
        } catch (com.github.dockerjava.api.exception.NotModifiedException e) {
            // Docker throws this when the container is already running — for example when
            // a previous /run or /restart call's health check timed out on a slow-booting
            // app (see RunService's HEALTH_CHECK_ATTEMPTS) even though the container itself
            // came up fine. That's not a failure to restart; the container just never
            // stopped. Fall through and re-check health instead of surfacing a 500.
        }

        // hostPort is the same fixed binding created back at /run time (see runContainer's
        // Ports.bind(...)) — it doesn't change across stop/start of the same container, so
        // there's no need to ask Docker for it. (An earlier version of this method tried to
        // read it back from the container's live NetworkSettings, which is only populated
        // while the container is actually running — if the container had exited, that came
        // back empty and threw a NoSuchElementException instead of a clean result.)
        boolean healthy = interactive ? waitForContainerRunning(containerId) : waitForHealthy(hostPort);
        return new RunResult(containerId, hostPort, healthy);
    }
}
