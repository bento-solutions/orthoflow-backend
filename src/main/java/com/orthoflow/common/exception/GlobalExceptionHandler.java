package com.orthoflow.common.exception;

import com.orthoflow.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Maps the exception taxonomy to RFC 7807 problem+json responses instead of
 * letting raw RuntimeExceptions reach the client as opaque 500s. Every
 * response carries a correlation id so a report from a user can be tied back
 * to a specific log line.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail handleNotFound(NotFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler({ValidationException.class, IllegalArgumentException.class})
    public ProblemDetail handleBadRequest(RuntimeException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler({ConflictException.class, IllegalStateException.class})
    public ProblemDetail handleConflict(RuntimeException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleDataIntegrityViolation(DataIntegrityViolationException ex, HttpServletRequest request) {
        String message = String.valueOf(ex.getMostSpecificCause().getMessage());
        // The V21 exclusion constraint is the one integrity violation with a
        // message worth translating for the user — it's the exact "two
        // bookings, same chair, overlapping time" race the constraint
        // exists to catch (audit VIII.6 / P2 #29). Everything else maps to
        // a generic conflict rather than leaking a raw SQL error string.
        if (message.contains("appointments_no_chair_overlap")) {
            return problem(HttpStatus.CONFLICT,
                    "This chair is already booked for an overlapping time slot.", request);
        }
        return problem(HttpStatus.CONFLICT, "This action conflicts with existing data.", request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest request) {
        return problem(HttpStatus.CONFLICT,
                "This record was changed by someone else while you were editing it. Reload and try again.", request);
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ProblemDetail handleUnauthorized(UnauthorizedException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        return problem(HttpStatus.FORBIDDEN, "You do not have permission to perform this action", request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        String detail = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> e.getField() + ": " + e.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return problem(HttpStatus.BAD_REQUEST, detail.isEmpty() ? "Validation failed" : detail, request);
    }

    // ── Malformed request → 400, not 500 ────────────────────────────────
    // These were all falling through to handleUnexpected: a broken JSON body,
    // a non-UUID path variable, a missing query param — ordinary client
    // mistakes — logged themselves as "Unhandled exception" and came back 500.

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "The request body is missing or is not valid JSON.", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String name = ex.getName();
        String required = ex.getRequiredType() == null ? "the expected type" : ex.getRequiredType().getSimpleName();
        return problem(HttpStatus.BAD_REQUEST, "'" + name + "' is not a valid " + required + ".", request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParam(MissingServletRequestParameterException ex, HttpServletRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "Required parameter '" + ex.getParameterName() + "' is missing.", request);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleUploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "That upload is larger than this endpoint accepts.", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "That HTTP method is not supported on this endpoint.", request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, "No endpoint at " + request.getRequestURI() + ".", request);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Reuse the request's own correlation id (set by CorrelationIdFilter
        // and already on every log line for this request via MDC) rather
        // than minting a fresh one here — a fresh id at error time couldn't
        // be grepped against the request's earlier log lines.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        ProblemDetail problem = problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Reference: " + correlationId, request);
        problem.setProperty("correlationId", correlationId);
        return problem;
    }

    private ProblemDetail problem(HttpStatus status, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("path", request.getRequestURI());
        return problem;
    }
}
