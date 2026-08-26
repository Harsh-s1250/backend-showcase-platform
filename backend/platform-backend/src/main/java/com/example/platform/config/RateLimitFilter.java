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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-window, in-memory rate limiter for the public-facing endpoints named in the security
 * pass (§17 item 1): the reverse proxy, showcase page, OpenAPI/UI-schema/experience resolvers,
 * and GitHub login kickoff. These have no auth in front of them, so without this they could be
 * hit at unlimited frequency by anyone.
 *
 * Deliberately simple: no external dependency (no Bucket4j/Redis), just a ConcurrentHashMap keyed
 * by client IP + rule name, reset every window. This matches the platform's existing
 * single-instance, in-memory-session architecture (see handoff gotcha on session store) — if this
 * ever runs across multiple instances, swap this for Bucket4j+Redis or similar, since counters
 * here are per-JVM and won't be shared.
 *
 * Does NOT cover authenticated management endpoints (clone/analyze/build/run/etc.) — those
 * already require a logged-in, owned session, which is a much stronger gate than IP-based limits.
 * Does NOT cover the terminal WebSocket — that has its own dedicated protections (single session,
 * time cap, message size cap) described in the handoff.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    private record Rule(String name, String pathPattern, int limit, long windowMillis) {}

    // Order matters only in that the first matching rule wins per request.
    private static final List<Rule> RULES = List.of(
            new Rule("auth-login", "/auth/login", 10, 60_000),
            new Rule("proxy", "/p/**", 120, 60_000),
            new Rule("showcase", "/api/projects/*/showcase", 60, 60_000),
            new Rule("openapi", "/api/projects/*/openapi", 60, 60_000),
            new Rule("experience", "/api/projects/*/experience", 60, 60_000),
            new Rule("ui-schema", "/api/projects/*/ui-schema", 60, 60_000)
    );

    private record Window(AtomicInteger count, AtomicLong windowStartMillis) {}

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

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

        // getRemoteAddr(), not X-Forwarded-For — this app isn't behind a trusted reverse proxy
        // today, and honoring a client-supplied header here would make the limiter trivially
        // bypassable (attacker just sets a different X-Forwarded-For value per request).
        String clientIp = request.getRemoteAddr();
        String key = matched.name() + ":" + clientIp;

        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, k -> new Window(new AtomicInteger(0), new AtomicLong(now)));

        synchronized (window) {
            long elapsed = now - window.windowStartMillis().get();
            if (elapsed > matched.windowMillis()) {
                window.windowStartMillis().set(now);
                window.count().set(0);
            }

            int current = window.count().incrementAndGet();
            if (current > matched.limit()) {
                long retryAfterSeconds = Math.max(1, (matched.windowMillis() - elapsed) / 1000);
                response.setStatus(429);
                response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));
                response.setContentType("application/json");
                response.getWriter().write(
                        "{\"error\":\"Too many requests\",\"message\":\"Rate limit exceeded for this endpoint. Try again in "
                                + retryAfterSeconds + " seconds.\"}"
                );
                return;
            }
        }

        chain.doFilter(request, response);
    }
}
