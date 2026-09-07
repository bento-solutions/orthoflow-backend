package com.orthoflow.platform.security;

import com.orthoflow.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * What an unauthenticated request to a protected endpoint gets back.
 *
 * <p>Spring Security's default with no form login / basic auth configured is
 * {@code Http403ForbiddenEntryPoint} — so a missing, expired, or revoked token
 * produced a <strong>403</strong>, indistinguishable from "authenticated but
 * wrong role". The frontend interceptor only clears the session on a 401, so an
 * expired token left the SPA wedged: every call failed and nothing logged the
 * user out. This makes "not authenticated" a 401 again, in the same
 * problem+json shape {@code GlobalExceptionHandler} uses.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        response.getWriter().write("""
                {"type":"about:blank","title":"Unauthorized","status":401,\
                "detail":"Authentication is required or the session has expired.",\
                "path":"%s","correlationId":"%s"}"""
                .formatted(escape(request.getRequestURI()), escape(correlationId)));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
