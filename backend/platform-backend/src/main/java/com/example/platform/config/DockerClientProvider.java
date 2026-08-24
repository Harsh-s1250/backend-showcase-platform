package com.example.platform.config;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Extracted from RunService so the new terminal/attach feature can share the same Docker
 * client instead of each service opening its own connection to the daemon.
 *
 * ⚠️ tcp://localhost:2375 is the same deliberate local-dev-only workaround documented in
 * the handoff (Milestone 6, Windows named-pipe bug) — must never be replicated on a real
 * deployed server.
 */
@Configuration
public class DockerClientProvider {

    // Shared with ConsoleAttachSession, which bypasses docker-java's own attach
    // implementation entirely (see that class's Javadoc for why) and talks to the same
    // daemon directly over a raw socket.
    public static final String DOCKER_HOST = "localhost";
    public static final int DOCKER_PORT = 2375;

    @Bean
    public DockerClient dockerClient() {
        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost("tcp://" + DOCKER_HOST + ":" + DOCKER_PORT)
                .build();
        ApacheDockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();
        return DockerClientImpl.getInstance(config, httpClient);
    }
}
