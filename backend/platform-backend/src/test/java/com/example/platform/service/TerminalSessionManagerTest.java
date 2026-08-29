package com.example.platform.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PRD §25: "One active interactive terminal session per project at a time." This is the class
 * that enforces it, and the property that actually matters is a concurrency guarantee — two
 * browser tabs opening a WebSocket to the same project at close to the same instant must not
 * both win. A single-threaded test wouldn't exercise the actual race at all (ConcurrentHashMap's
 * computeIfAbsent is what's doing the real work here), so this test deliberately fires many
 * threads at the same projectId simultaneously via a CountDownLatch starting gate.
 *
 * Uses a fake AttachSession (see AttachSession) rather than a real ConsoleAttachSession, since
 * the real one connects to Docker over a socket in its constructor — not something a unit test
 * should depend on having available.
 */
class TerminalSessionManagerTest {

    private static class FakeAttachSession implements AttachSession {
        private final AtomicInteger closeCount;
        private volatile boolean closed = false;

        FakeAttachSession(AtomicInteger closeCount) {
            this.closeCount = closeCount;
        }

        @Override
        public void sendInput(String text) { /* no-op fake */ }

        @Override
        public void close() {
            closed = true;
            closeCount.incrementAndGet();
        }
    }

    @Test
    void tryAcquire_singleCaller_succeeds() {
        AtomicInteger closeCount = new AtomicInteger(0);
        TerminalSessionManager manager =
                new TerminalSessionManager((containerId, onOutput, onExit) -> new FakeAttachSession(closeCount));

        Optional<AttachSession> result = manager.tryAcquire(UUID.randomUUID(), "container-1", s -> {}, () -> {});

        assertThat(result).isPresent();
    }

    @Test
    void tryAcquire_secondCallerForSameProject_isRejectedWhileFirstIsActive() {
        AtomicInteger closeCount = new AtomicInteger(0);
        TerminalSessionManager manager =
                new TerminalSessionManager((containerId, onOutput, onExit) -> new FakeAttachSession(closeCount));
        UUID projectId = UUID.randomUUID();

        Optional<AttachSession> first = manager.tryAcquire(projectId, "container-1", s -> {}, () -> {});
        Optional<AttachSession> second = manager.tryAcquire(projectId, "container-1", s -> {}, () -> {});

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
    }

    @Test
    void tryAcquire_differentProjects_bothSucceedIndependently() {
        AtomicInteger closeCount = new AtomicInteger(0);
        TerminalSessionManager manager =
                new TerminalSessionManager((containerId, onOutput, onExit) -> new FakeAttachSession(closeCount));

        Optional<AttachSession> first = manager.tryAcquire(UUID.randomUUID(), "container-1", s -> {}, () -> {});
        Optional<AttachSession> second = manager.tryAcquire(UUID.randomUUID(), "container-2", s -> {}, () -> {});

        assertThat(first).isPresent();
        assertThat(second).isPresent();
    }

    @Test
    void release_thenTryAcquire_succeedsAgain() {
        AtomicInteger closeCount = new AtomicInteger(0);
        TerminalSessionManager manager =
                new TerminalSessionManager((containerId, onOutput, onExit) -> new FakeAttachSession(closeCount));
        UUID projectId = UUID.randomUUID();

        manager.tryAcquire(projectId, "container-1", s -> {}, () -> {});
        manager.release(projectId);
        Optional<AttachSession> secondAttempt = manager.tryAcquire(projectId, "container-1", s -> {}, () -> {});

        assertThat(secondAttempt).isPresent();
        assertThat(closeCount.get())
                .as("the first session should have been closed by release()")
                .isEqualTo(1);
    }

    /**
     * The actual concurrency guarantee — 50 threads all racing to acquire the same projectId
     * at (as close as a JVM can arrange) the same instant. Exactly one must win. This is the
     * test that would have failed if computeIfAbsent were ever replaced with a naive
     * containsKey()-then-put() check (a classic non-atomic race), which is a realistic mistake
     * for this exact "acquire a slot" pattern.
     */
    @Test
    void tryAcquire_underConcurrentRace_exactlyOneCallerWins() throws InterruptedException {
        AtomicInteger closeCount = new AtomicInteger(0);
        TerminalSessionManager manager =
                new TerminalSessionManager((containerId, onOutput, onExit) -> new FakeAttachSession(closeCount));
        UUID projectId = UUID.randomUUID();

        int threadCount = 50;
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startingGate = new CountDownLatch(1);

        List<Future<Optional<AttachSession>>> futures = IntStream.range(0, threadCount)
                .mapToObj(i -> pool.submit(() -> {
                    startingGate.await();
                    return manager.tryAcquire(projectId, "container-1", s -> {}, () -> {});
                }))
                .collect(Collectors.toList());

        startingGate.countDown(); // release all threads at once
        pool.shutdown();
        assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        long winners = futures.stream()
                .map(f -> {
                    try {
                        return f.get();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                })
                .filter(Optional::isPresent)
                .count();

        assertThat(winners).isEqualTo(1);
    }
}
