package com.orthoflow.platform.security;

import com.orthoflow.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * What an authenticated request to an endpoint the caller's role may not use
 * gets back: a 403 in problem+json.
 *
 * <p>{@code GlobalExceptionHandler} already renders {@link AccessDeniedException}
 * for the ones thrown from {@code @PreAuthorize} <em>inside</em> the dispatcher,
 * but a denial from {@code SecurityConfig}'s path matrix happens in the filter
 * chain, before {@code @ControllerAdvice} can see it. This gives that case the
 * same body shape instead of Spring's default HTML error page.
 */
@Component
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        response.getWriter().write("""
                {"type":"about:blank","title":"Forbidden","status":403,\
                "detail":"You do not have permission to perform this action.",\
                "path":"%s","correlationId":"%s"}"""
                .formatted(escape(request.getRequestURI()), escape(correlationId)));
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
