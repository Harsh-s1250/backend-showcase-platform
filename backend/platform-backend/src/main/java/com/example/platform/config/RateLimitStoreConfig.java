package com.example.platform.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RateLimitStoreConfig {

    /**
     * Only created when app.ratelimit.store=redis is explicitly set. Requires a
     * StringRedisTemplate bean to exist, which spring-boot-starter-data-redis auto-configures
     * once spring.data.redis.host/port point at a reachable Redis — see application.properties
     * for the (commented-out, opt-in) connection settings.
     */
    @Bean
    @ConditionalOnProperty(prefix = "app.ratelimit", name = "store", havingValue = "redis")
    public RateLimitStore redisRateLimitStore(StringRedisTemplate redisTemplate) {
        return new RedisRateLimitStore(redisTemplate);
    }

    /**
     * The default: in-memory, single-instance store. Only backs off if some other configuration
     * (e.g. the redis bean above) already provided a RateLimitStore.
     */
    @Bean
    @ConditionalOnMissingBean(RateLimitStore.class)
    public RateLimitStore inMemoryRateLimitStore() {
        return new InMemoryRateLimitStore();
    }
}
