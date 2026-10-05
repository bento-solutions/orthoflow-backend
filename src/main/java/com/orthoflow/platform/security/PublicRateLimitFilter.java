package com.orthoflow.platform.security;

import com.orthoflow.common.web.CorrelationIdFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-IP throttle on the public surface (online booking, registration, surveys).
 * Writes are held to a much lower ceiling than reads: a form that creates a
 * request for staff is the thing worth spamming. Same fixed-window, in-memory,
 * per-instance design as {@link AuthRateLimitFilter}, and the same caveat — move
 * it to the proxy if the API is ever scaled out.
 *
 * <p>Not a Spring bean on its own: {@link PublicSecurityConfig} adds it to the
 * public chain only, so it never runs for authenticated routes.
 */
public class PublicRateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(PublicRateLimitFilter.class);

    private final int maxReads;
    private final int maxWrites;
    private final Duration window;
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    public PublicRateLimitFilter(int maxReads, int maxWrites, long windowSeconds) {
        this.maxReads = maxReads;
        this.maxWrites = maxWrites;
        this.window = Duration.ofSeconds(windowSeconds);
    }

    /** Webhooks authenticate by signature and arrive in bursts from one service; only the public pages are throttled. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getServletPath().startsWith("/webhooks/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean write = !"GET".equalsIgnoreCase(request.getMethod()) && !"OPTIONS".equalsIgnoreCase(request.getMethod());
        String key = (write ? "w|" : "r|") + clientIp(request);
        Instant now = Instant.now();
        Counter counter = counters.compute(key, (k, existing) ->
                existing == null || now.isAfter(existing.resetsAt) ? new Counter(now.plus(window)) : existing);
        int used = counter.hits.incrementAndGet();
        if (counters.size() > 10_000) {
            counters.entrySet().removeIf(e -> now.isAfter(e.getValue().resetsAt));
        }
        if (used > (write ? maxWrites : maxReads)) {
            long retryAfter = Math.max(1, Duration.between(now, counter.resetsAt).getSeconds());
            log.warn("Public rate limit hit: {} {} requests from {}", used, write ? "write" : "read", clientIp(request));
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setHeader("Retry-After", Long.toString(retryAfter));
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"Too Many Requests\",\"status\":429,"
                    + "\"detail\":\"Too many requests. Try again in " + retryAfter + " seconds.\",\"correlationId\":\""
                    + String.valueOf(MDC.get(CorrelationIdFilter.MDC_KEY)).replace("\"", "") + "\"}");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        return forwarded != null && !forwarded.isBlank() ? forwarded.split(",")[0].trim() : request.getRemoteAddr();
    }

    private static final class Counter {
        private final Instant resetsAt;
        private final AtomicInteger hits = new AtomicInteger();

        private Counter(Instant resetsAt) {
            this.resetsAt = resetsAt;
        }
    }
}
