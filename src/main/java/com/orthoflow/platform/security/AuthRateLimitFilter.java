package com.orthoflow.platform.security;

import com.orthoflow.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A blunt, dependency-free throttle on the unauthenticated auth endpoints.
 *
 * <p>{@code /auth/login}, {@code /auth/forgot-password} and
 * {@code /auth/reset-password} are {@code permitAll} and were completely
 * unthrottled: no lockout, no backoff, so an attacker could grind login
 * guesses or use forgot-password as a mail cannon (audit H6). This caps
 * attempts per client IP per fixed window (default 20 / 5 min, both
 * configurable) and answers a burst with 429 + {@code Retry-After}.
 *
 * <p>In-memory and per-instance, which is enough for the single-container
 * deployment this runs in. If the API is ever scaled out, move this to a
 * shared store (Redis) or the reverse proxy.
 */
@Component
@Slf4j
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final String PATH_PREFIX = "/auth/";

    /** Endpoints worth throttling — the ones that are public and abusable. */
    private static final Map<String, Boolean> THROTTLED = Map.of(
            "/auth/login", true,
            "/auth/forgot-password", true,
            "/auth/reset-password", true);

    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public AuthRateLimitFilter(
            // 20 / 5 min per IP: a small clinic behind one NAT'd public IP can
            // still have several staff sign in (with the odd typo) on a Monday
            // morning, while a brute-force run — which needs thousands — is
            // stopped cold. Tune per deployment.
            @Value("${orthoflow.auth.rate-limit.max-attempts:20}") int maxAttempts,
            @Value("${orthoflow.auth.rate-limit.window-seconds:300}") long windowSeconds) {
        this.maxAttempts = maxAttempts;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        int ctx = path.indexOf(PATH_PREFIX);
        String logical = ctx >= 0 ? path.substring(ctx) : path;

        if (!"POST".equalsIgnoreCase(request.getMethod()) || !THROTTLED.containsKey(logical)) {
            chain.doFilter(request, response);
            return;
        }

        String key = logical + '|' + clientIp(request);
        Instant now = Instant.now();
        Counter counter = counters.compute(key, (k, existing) -> {
            if (existing == null || existing.expired(now)) {
                return new Counter(now.plus(window));
            }
            return existing;
        });
        int used = counter.hits.incrementAndGet();

        if (counters.size() > 10_000) {
            counters.entrySet().removeIf(e -> e.getValue().expired(now));
        }

        if (used > maxAttempts) {
            long retryAfter = Math.max(1, Duration.between(now, counter.resetsAt).getSeconds());
            log.warn("Auth rate limit hit: {} attempts on {} from {} (limit {}/{}s)",
                    used, logical, clientIp(request), maxAttempts, window.getSeconds());
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            response.getWriter().write("""
                    {"type":"about:blank","title":"Too Many Requests","status":429,\
                    "detail":"Too many attempts. Try again in %d seconds.",\
                    "path":"%s","correlationId":"%s"}"""
                    .formatted(retryAfter, escape(path), escape(correlationId)));
            return;
        }

        chain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class Counter {
        private final Instant resetsAt;
        private final AtomicInteger hits = new AtomicInteger();

        private Counter(Instant resetsAt) {
            this.resetsAt = resetsAt;
        }

        private boolean expired(Instant now) {
            return now.isAfter(resetsAt);
        }
    }
}
