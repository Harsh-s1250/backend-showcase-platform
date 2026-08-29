package com.example.platform.ws;

import com.example.platform.analyzer.ProjectType;
import com.example.platform.dto.TerminalMessage;
import com.example.platform.entity.Project;
import com.example.platform.repository.ProjectRepository;
import com.example.platform.service.AttachSession;
import com.example.platform.service.RunService;
import com.example.platform.service.TerminalSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket /api/projects/{id}/terminal — PRD §32. Exposes only a console app's own
 * stdin/stdout, never a host shell (PRD §27); see ConsoleAttachSession for why that's true
 * by construction rather than by a permission check.
 *
 * Deliberately public, same reasoning as the rest of the showcase experience — but per
 * PRD §28 ("Public Access Security"), this is exactly the endpoint that needs its own
 * guardrails: one session per project (§25), a maximum session duration, and a capped
 * message size (set at the container level — see WebSocketConfig).
 */
@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);
    private static final long MAX_SESSION_DURATION_MINUTES = 15;

    private final ProjectRepository projectRepository;
    private final TerminalSessionManager sessionManager;
    private final RunService runService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService scheduler;

    private final Map<String, UUID> projectIdBySessionId = new ConcurrentHashMap<>();
    private final Map<String, AttachSession> attachBySessionId = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> timeoutBySessionId = new ConcurrentHashMap<>();
    // Sending to the same WebSocketSession concurrently isn't safe (Spring's own docs say
    // so) — the attach callback thread and this handler's thread both write to it.
    private final Map<String, Object> sendLockBySessionId = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(ProjectRepository projectRepository,
                                     TerminalSessionManager sessionManager,
                                     RunService runService,
                                     ScheduledExecutorService scheduler) {
        this.projectRepository = projectRepository;
        this.sessionManager = sessionManager;
        this.runService = runService;
        this.scheduler = scheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        UUID projectId = (UUID) session.getAttributes().get("projectId");
        sendLockBySessionId.put(session.getId(), new Object());

        if (projectId == null) {
            closeWithError(session, "Missing project id.");
            return;
        }

        Project project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            closeWithError(session, "Project not found.");
            return;
        }
        if (project.getProjectTypeEnum() != ProjectType.CONSOLE_APPLICATION) {
            closeWithError(session, "This project isn't a console application.");
            return;
        }
        if (project.getContainerId() == null || !runService.isContainerRunning(project.getContainerId())) {
            closeWithError(session, "Project is not currently running.");
            return;
        }

        projectIdBySessionId.put(session.getId(), projectId);

        Optional<AttachSession> acquired;
        try {
            acquired = sessionManager.tryAcquire(
                    projectId,
                    project.getContainerId(),
                    text -> send(session, new TerminalMessage("output", text)),
                    () -> send(session, new TerminalMessage("exit", "Application exited."))
            );
        } catch (Exception e) {
            // ConsoleAttachSession's constructor connects to Docker synchronously and throws
            // if that connection fails — don't let that propagate out of a WebSocket
            // lifecycle callback uncaught.
            log.warn("Failed to attach terminal session for project {}", projectId, e);
            closeWithError(session, "Could not connect to the running application.");
            return;
        }

        if (acquired.isEmpty()) {
            send(session, new TerminalMessage("busy", "This application is currently being used. Please try again later."));
            closeQuietly(session, CloseStatus.NORMAL);
            return;
        }

        attachBySessionId.put(session.getId(), acquired.get());
        send(session, new TerminalMessage("info", "Connected."));

        ScheduledFuture<?> timeout = scheduler.schedule(() -> {
            send(session, new TerminalMessage("info", "Session time limit reached."));
            closeQuietly(session, CloseStatus.NORMAL);
        }, MAX_SESSION_DURATION_MINUTES, TimeUnit.MINUTES);
        timeoutBySessionId.put(session.getId(), timeout);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            TerminalMessage parsed = objectMapper.readValue(message.getPayload(), TerminalMessage.class);
            if ("input".equals(parsed.type()) && parsed.data() != null) {
                AttachSession attach = attachBySessionId.get(session.getId());
                if (attach != null) {
                    log.info("Forwarding {} bytes of terminal input to container", parsed.data().length());
                    attach.sendInput(parsed.data());
                } else {
                    log.warn("Received terminal input but no attach session is registered for this websocket session");
                }
            }
        } catch (Exception e) {
            // Malformed frame — don't tear down the whole session over one bad message, but
            // DO log it: a silent catch here once hid a real deserialization bug (see
            // TerminalMessage's @JsonProperty annotations) for an entire terminal session.
            log.warn("Failed to handle terminal input message: {}", e.toString());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        UUID projectId = projectIdBySessionId.remove(session.getId());
        if (projectId != null) sessionManager.release(projectId);

        attachBySessionId.remove(session.getId());
        sendLockBySessionId.remove(session.getId());

        ScheduledFuture<?> timeout = timeoutBySessionId.remove(session.getId());
        if (timeout != null) timeout.cancel(false);
    }

    private void send(WebSocketSession session, TerminalMessage message) {
        Object lock = sendLockBySessionId.get(session.getId());
        if (lock == null || !session.isOpen()) return;
        synchronized (lock) {
            try {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
            } catch (Exception ignored) {
                // Session likely closing concurrently — nothing useful to do about it.
            }
        }
    }

    private void closeWithError(WebSocketSession session, String message) {
        send(session, new TerminalMessage("error", message));
        closeQuietly(session, CloseStatus.NORMAL);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception ignored) {}
    }
}
