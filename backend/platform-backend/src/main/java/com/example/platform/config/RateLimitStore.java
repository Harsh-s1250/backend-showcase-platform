package com.example.platform.config;

/**
 * Where rate-limit counters actually live. Swappable so the same {@link RateLimitFilter} logic
 * works whether counters are per-JVM (fine for one instance) or shared across instances behind
 * a load balancer (needs a real shared store like Redis) — see handoff §3 "multi-instance
 * deployment" and the filter's own class-level note.
 */
public interface RateLimitStore {

    record AcquireResult(boolean allowed, long retryAfterSeconds) {}

    /**
     * Registers one request against {@code key}'s fixed window and reports whether it's still
     * within {@code limit}. Implementations must be safe for concurrent calls with the same key.
     */
    AcquireResult tryAcquire(String key, int limit, long windowSeconds);
}
