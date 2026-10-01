package com.orthoflow.voice.infrastructure.ratelimit;

import com.orthoflow.common.security.AuthenticatedUser;
import com.orthoflow.common.web.CorrelationIdFilter;
import com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimiter.Permit;
import com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimiter.Refused;
import com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimiter.Route;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.regex.Pattern;

/**
 * Applies {@link VoiceRateLimiter} to the paid voice routes.
 *
 * <p>A filter rather than a controller guard so a refused request is turned
 * away before the multipart body of a clip is read. It runs after Spring
 * Security's own chain (Boot orders plain filter beans behind it), so the
 * caller is already identified by their verified token; an unauthenticated
 * request is passed on untouched for the security rules to answer.
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Slf4j
public class VoiceRateLimitFilter extends OncePerRequestFilter {

    private static final Pattern SUMMARIZE =
            Pattern.compile("^/voice/sessions/[0-9a-fA-F-]{36}/summarize/?$");

    private static final Pattern EXTRACT =
            Pattern.compile("^/consultations/[0-9a-fA-F-]{36}/extract/?$");

    private final VoiceRateLimiter limiter;

    public VoiceRateLimitFilter(VoiceRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Route route = routeOf(request);
        AuthenticatedUser user = route == null ? null : callerOf(SecurityContextHolder.getContext().getAuthentication());
        if (route == null || user == null) {
            chain.doFilter(request, response);
            return;
        }

        Permit permit;
        try {
            permit = limiter.acquire(route, user.id());
        } catch (Refused refused) {
            log.warn("Voice rate limit: {} refused for user {} ({})", route, user.id(), refused.getMessage());
            refuse(request, response, refused);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            permit.close();
        }
    }

    private static Route routeOf(HttpServletRequest request) {
        if (!"POST".equalsIgnoreCase(request.getMethod())) {
            return null;
        }
        String uri = request.getRequestURI();
        String context = request.getContextPath();
        String path = context != null && !context.isEmpty() && uri.startsWith(context)
                ? uri.substring(context.length())
                : uri;
        if (path.equals("/voice/transcribe")) return Route.TRANSCRIBE;
        if (path.equals("/voice/interpret")) return Route.INTERPRET;
        if (SUMMARIZE.matcher(path).matches()) return Route.SUMMARIZE;
        if (EXTRACT.matcher(path).matches()) return Route.EXTRACT;
        return null;
    }

    private static AuthenticatedUser callerOf(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    private static void refuse(HttpServletRequest request, HttpServletResponse response, Refused refused)
            throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader("Retry-After", Long.toString(refused.retryAfterSeconds()));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        response.getWriter().write("""
                {"type":"about:blank","title":"Too Many Requests","status":429,\
                "detail":"%s","path":"%s","correlationId":"%s"}"""
                .formatted(escape(refused.getMessage()), escape(request.getRequestURI()), escape(correlationId)));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
