package com.example.platform.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Fixed-window rate limiter for the public-facing endpoints named in the security pass (§17
 * item 1): the reverse proxy, showcase page, OpenAPI/UI-schema/experience resolvers, and GitHub
 * login kickoff. These have no auth in front of them, so without this they could be hit at
 * unlimited frequency by anyone.
 *
 * The actual counting is delegated to a {@link RateLimitStore} (see RateLimitStoreConfig) —
 * in-memory by default (single-instance, per-JVM, matches this platform's existing in-memory
 * session architecture), swappable to a Redis-backed store via {@code app.ratelimit.store=redis}
 * once this ever needs to run across multiple instances. Per-rule limit/window values can be
 * tuned via {@code app.ratelimit.rules.<name>.limit} / {@code .window-seconds} without a code
 * change or recompile (see RateLimitProperties) — path patterns and rule names themselves stay
 * in code since those are structural, not tuning knobs.
 *
 * Does NOT cover authenticated management endpoints (clone/analyze/build/run/etc.) — those
 * already require a logged-in, owned session, which is a much stronger gate than IP-based limits.
 * Does NOT cover the terminal WebSocket — that has its own dedicated protections (single session,
 * time cap, message size cap) described in the handoff.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private record Rule(String name, String pathPattern, int defaultLimit, long defaultWindowSeconds) {}

    // Order matters only in that the first matching rule wins per request.
    private static final List<Rule> RULES = List.of(
            new Rule("auth-login", "/auth/login", 10, 60),
            new Rule("proxy", "/p/**", 120, 60),
            new Rule("showcase", "/api/projects/*/showcase", 60, 60),
            new Rule("openapi", "/api/projects/*/openapi", 60, 60),
            new Rule("experience", "/api/projects/*/experience", 60, 60),
            new Rule("ui-schema", "/api/projects/*/ui-schema", 60, 60)
    );

    private final RateLimitStore store;
    private final RateLimitProperties properties;

    public RateLimitFilter(RateLimitStore store, RateLimitProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Rule matched = null;
        for (Rule rule : RULES) {
            if (PATH_MATCHER.match(rule.pathPattern(), request.getRequestURI())) {
                matched = rule;
                break;
            }
        }

        if (matched == null) {
            chain.doFilter(request, response);
            return;
        }

        RateLimitProperties.RuleOverride override = properties.getRules().get(matched.name());
        int limit = (override != null && override.getLimit() != null) ? override.getLimit() : matched.defaultLimit();
        long windowSeconds = (override != null && override.getWindowSeconds() != null)
                ? override.getWindowSeconds()
                : matched.defaultWindowSeconds();

        // getRemoteAddr(), not X-Forwarded-For — this app isn't behind a trusted reverse proxy
        // today, and honoring a client-supplied header here would make the limiter trivially
        // bypassable (attacker just sets a different X-Forwarded-For value per request).
        String clientIp = request.getRemoteAddr();
        String key = matched.name() + ":" + clientIp;

        RateLimitStore.AcquireResult result = store.tryAcquire(key, limit, windowSeconds);

        if (!result.allowed()) {
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(result.retryAfterSeconds()));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"Too many requests\",\"message\":\"Rate limit exceeded for this endpoint. Try again in "
                            + result.retryAfterSeconds() + " seconds.\"}"
            );
            return;
        }

        chain.doFilter(request, response);
    }
}

