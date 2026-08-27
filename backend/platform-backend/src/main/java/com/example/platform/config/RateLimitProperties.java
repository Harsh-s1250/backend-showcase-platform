package com.example.platform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Externalizes the two things about rate limiting that are legitimately environment-specific:
 * which store backs the counters, and what the actual limits/windows are. Path patterns and
 * rule names stay in RateLimitFilter's code — those are structural, not tuning knobs.
 *
 * Example application.properties overrides:
 *   app.ratelimit.store=memory
 *   app.ratelimit.rules.auth-login.limit=20
 *   app.ratelimit.rules.auth-login.window-seconds=60
 *
 * Any rule not overridden here falls back to RateLimitFilter's built-in defaults.
 */
@Component
@ConfigurationProperties(prefix = "app.ratelimit")
public class RateLimitProperties {

    /** "memory" (default) or "redis". */
    private String store = "memory";

    private Map<String, RuleOverride> rules = new HashMap<>();

    public String getStore() { return store; }
    public void setStore(String store) { this.store = store; }

    public Map<String, RuleOverride> getRules() { return rules; }
    public void setRules(Map<String, RuleOverride> rules) { this.rules = rules; }

    public static class RuleOverride {
        private Integer limit;
        private Long windowSeconds;

        public Integer getLimit() { return limit; }
        public void setLimit(Integer limit) { this.limit = limit; }

        public Long getWindowSeconds() { return windowSeconds; }
        public void setWindowSeconds(Long windowSeconds) { this.windowSeconds = windowSeconds; }
    }
}
