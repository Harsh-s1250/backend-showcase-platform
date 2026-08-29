package com.example.platform.service;

import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

@Service
public class TerminalSessionManager {

    /** Constructs the live attach session — real Docker attach in production, a fake in tests. */
    @FunctionalInterface
    public interface SessionFactory {
        AttachSession create(String containerId, Consumer<String> onOutput, Runnable onExit);
    }

    private final SessionFactory sessionFactory;
    private final ConcurrentHashMap<UUID, AttachSession> activeByProject = new ConcurrentHashMap<>();

    public TerminalSessionManager() {
        this(ConsoleAttachSession::new);
    }

    // Package-private: lets TerminalSessionManagerTest substitute a fake AttachSession instead
    // of a real one, since ConsoleAttachSession's constructor makes a real Docker socket
    // connection as a side effect — not something a unit test should depend on. Spring still
    // uses the no-arg constructor above for the real @Service bean.
    TerminalSessionManager(SessionFactory sessionFactory) {
        this.sessionFactory = sessionFactory;
    }

    /**
     * Attempts to become the one active terminal session for this project (PRD §25 —
     * "One active interactive terminal session per project at a time"). Returns empty if
     * someone else already holds it; the caller is expected to tell the user
     * "This application is currently being used. Please try again later." per the PRD's
     * exact wording, rather than queueing or bumping the existing session.
     */
    public Optional<AttachSession> tryAcquire(UUID projectId, String containerId,
                                                Consumer<String> onOutput, Runnable onExit) {
        AttachSession[] created = new AttachSession[1];
        AttachSession existing = activeByProject.computeIfAbsent(projectId, id -> {
            created[0] = sessionFactory.create(containerId, onOutput, () -> {
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
        AttachSession session = activeByProject.remove(projectId);
        if (session != null) {
            try {
                session.close();
            } catch (java.io.IOException e) {
                // AttachSession extends Closeable, whose close() is declared to throw
                // IOException — ConsoleAttachSession's real implementation never actually
                // does, but the interface has to allow it. Nothing useful to do about a
                // failure to close a session that's being torn down anyway.
            }
        }
    }
}
