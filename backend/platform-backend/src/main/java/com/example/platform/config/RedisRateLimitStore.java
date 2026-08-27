package com.example.platform.config;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis-backed rate limit store — shares counters across multiple backend instances, unlike
 * {@link InMemoryRateLimitStore}. Only ever instantiated when {@code app.ratelimit.store=redis}
 * is explicitly set (see RateLimitStoreConfig); otherwise this class is never touched and no
 * connection to Redis is attempted. Not currently exercised against a live Redis instance —
 * verify end-to-end (a request that legitimately hits 429, and that the Retry-After header is
 * sane) before relying on this in a real multi-instance deployment.
 */
public class RedisRateLimitStore implements RateLimitStore {

    private static final String KEY_PREFIX = "ratelimit:";

    private final StringRedisTemplate redisTemplate;

    public RedisRateLimitStore(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public AcquireResult tryAcquire(String key, int limit, long windowSeconds) {
        String redisKey = KEY_PREFIX + key;

        // INCR is atomic in Redis, so concurrent requests from different instances still count
        // correctly. Only the request that creates the key (count == 1) sets its expiry, so the
        // window's start time isn't reset by every subsequent increment.
        Long count = redisTemplate.opsForValue().increment(redisKey);
        if (count != null && count == 1L) {
            redisTemplate.expire(redisKey, Duration.ofSeconds(windowSeconds));
        }

        if (count != null && count > limit) {
            Long ttlSeconds = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
            long retryAfterSeconds = (ttlSeconds != null && ttlSeconds > 0) ? ttlSeconds : windowSeconds;
            return new AcquireResult(false, retryAfterSeconds);
        }

        return new AcquireResult(true, 0);
    }
}
