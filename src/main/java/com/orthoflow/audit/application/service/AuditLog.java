package com.orthoflow.audit.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.common.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Map;
import java.util.UUID;

/**
 * Writes the security and patient-access audit trail (V65). Every row is written in its own
 * transaction, so it is kept when the action it describes is rolled back: a failed sign-in
 * throws, and a refused export must still leave its trace.
 *
 * <p>Who did it, from where: the signed-in user and the current request are read from the
 * ambient context unless the caller says otherwise. A failure to write never breaks the
 * action being audited (a clinic must be able to treat a patient while the audit table is
 * unwell), but it is logged as an error with the event, so it is noticed.
 *
 * <p>What goes in {@code detail}: names of things that changed and small facts (a role, a count,
 * a reason). Never a password, a token, or the content of a record.
 */
@Service
public class AuditLog {

    private static final Logger log = LoggerFactory.getLogger(AuditLog.class);

    @Builder
    public record Event(AuditCategory category, String action, UUID practiceId, UUID actorId, String actorLabel, UUID patientId,
                        String targetType, UUID targetId, String method, String route, Integer status, String ip,
                        Map<String, ?> detail) {
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public AuditLog(JdbcTemplate jdbc, ObjectMapper objectMapper, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(transactions);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Records an event; anything not given is taken from the signed-in user and the current request. */
    public void record(Event event) {
        try {
            AuthenticatedUser user = signedInUser();
            UUID practiceId = event.practiceId() != null ? event.practiceId() : user == null ? null : user.practiceId();
            UUID actorId = event.actorId() != null ? event.actorId() : user == null ? null : user.id();
            String actorLabel = event.actorLabel() != null ? event.actorLabel() : user == null ? null : user.email();
            HttpServletRequest request = currentRequest();
            String ip = event.ip() != null ? event.ip() : request == null ? null : clientIp(request);
            String json = event.detail() == null || event.detail().isEmpty() ? null : objectMapper.writeValueAsString(event.detail());
            tx.executeWithoutResult(s -> jdbc.update("""
                    INSERT INTO audit_log (id, practice_id, category, action, actor_id, actor_label, patient_id, target_type, target_id,
                                           method, route, status, ip, detail)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), practiceId, event.category().name(), event.action(), actorId, truncate(actorLabel, 255),
                    event.patientId(), event.targetType(), event.targetId(), event.method(), truncate(event.route(), 255), event.status(),
                    truncate(ip, 64), json));
        } catch (Exception e) {
            log.error("AUDIT WRITE FAILED for {} {} (patient {}, actor {}): {}", event.category(), event.action(), event.patientId(),
                    event.actorId(), e.toString());
        }
    }

    /** Shorthand for an event with a few details. */
    public void record(AuditCategory category, String action, UUID patientId, Map<String, ?> detail) {
        record(Event.builder().category(category).action(action).patientId(patientId).detail(detail).build());
    }

    /**
     * The address the request came from. Behind the reverse proxy the peer is the proxy, and the
     * proxy appends the address it saw to X-Forwarded-For: the LAST entry is the one a client cannot forge.
     */
    public static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] parts = forwarded.split(",");
            return parts[parts.length - 1].trim();
        }
        return request.getRemoteAddr();
    }

    private static AuthenticatedUser signedInUser() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getPrincipal() instanceof AuthenticatedUser user ? user : null;
    }

    private static HttpServletRequest currentRequest() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes ? attributes.getRequest() : null;
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
