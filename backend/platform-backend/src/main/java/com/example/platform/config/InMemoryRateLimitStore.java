package com.example.platform.config;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Default rate-limit store — per-JVM, in-memory, fixed-window counters. This is exactly the
 * behavior that already existed inline in RateLimitFilter before the store was made pluggable;
 * extracting it here is a refactor, not a behavior change. Fine for a single server instance;
 * resets on restart and doesn't share state across instances (see handoff §3, §6.12).
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private record Window(AtomicInteger count, AtomicLong windowStartMillis) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public AcquireResult tryAcquire(String key, int limit, long windowSeconds) {
        long windowMillis = windowSeconds * 1000L;
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(new AtomicInteger(0), new AtomicLong(now)));

        synchronized (window) {
            long elapsed = now - window.windowStartMillis().get();
            if (elapsed > windowMillis) {
                window.windowStartMillis().set(now);
                window.count().set(0);
                elapsed = 0;
            }

            int current = window.count().incrementAndGet();
            if (current > limit) {
                long retryAfterSeconds = Math.max(1, (windowMillis - elapsed) / 1000);
                return new AcquireResult(false, retryAfterSeconds);
            }
            return new AcquireResult(true, 0);
        }
    }
}
