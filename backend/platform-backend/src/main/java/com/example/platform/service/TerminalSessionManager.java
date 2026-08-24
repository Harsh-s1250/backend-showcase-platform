package com.example.platform.service;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class TerminalSessionManager {

    private final ConcurrentHashMap<UUID, ConsoleAttachSession> activeByProject = new ConcurrentHashMap<>();

    /**
     * Attempts to become the one active terminal session for this project (PRD §25 —
     * "One active interactive terminal session per project at a time"). Returns empty if
     * someone else already holds it; the caller is expected to tell the user
     * "This application is currently being used. Please try again later." per the PRD's
     * exact wording, rather than queueing or bumping the existing session.
     */
    public Optional<ConsoleAttachSession> tryAcquire(UUID projectId, String containerId,
                                                       Consumer<String> onOutput, Runnable onExit) {
        ConsoleAttachSession[] created = new ConsoleAttachSession[1];
        ConsoleAttachSession existing = activeByProject.computeIfAbsent(projectId, id -> {
            created[0] = new ConsoleAttachSession(containerId, onOutput, () -> {
                onExit.run();
                release(projectId);
            });
            return created[0];
        });
        // If computeIfAbsent returned something other than what we just created, another
        // session beat us to it — this attempt failed.
        return existing == created[0] ? Optional.of(existing) : Optional.empty();
    }

    public void release(UUID projectId) {
        ConsoleAttachSession session = activeByProject.remove(projectId);
        if (session != null) session.close();
    }
}
