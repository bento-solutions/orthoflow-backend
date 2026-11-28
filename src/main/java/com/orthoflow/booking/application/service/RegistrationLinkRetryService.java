package com.orthoflow.booking.application.service;

import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.application.service.MessageService;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.registration.application.service.RegistrationService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Retries personal registration-link issuance in the background if link generation
 * failed during booking creation, then sends the link by WhatsApp.
 */
@Service
@RequiredArgsConstructor
public class RegistrationLinkRetryService {

    private static final Logger log = LoggerFactory.getLogger(RegistrationLinkRetryService.class);
    private static final int MAX_ATTEMPTS = 10;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final RegistrationService registrationService;
    private final MessageService messages;
    private final Tenancy tenancy;

    @Transactional
    public void scheduleRetry(UUID practiceId, UUID patientId, UUID appointmentId, String phone, String language) {
        jdbc.update("""
                INSERT INTO registration_link_retries (id, practice_id, patient_id, appointment_id, recipient_phone, language)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), practiceId, patientId, appointmentId, phone, language == null ? "fr" : language);
    }

    @Scheduled(fixedDelayString = "${orthoflow.booking.link-retry-interval-ms:60000}", initialDelay = 30_000)
    public void tick() {
        processPendingRetries();
    }

    public int processPendingRetries() {
        List<Map<String, Object>> due = jdbc.queryForList("""
                SELECT id, practice_id, patient_id, appointment_id, recipient_phone, language, attempts
                FROM registration_link_retries
                WHERE completed_at IS NULL AND next_attempt_at <= NOW() AND attempts < ?
                ORDER BY next_attempt_at
                LIMIT 20
                """, MAX_ATTEMPTS);
        int processed = 0;
        for (Map<String, Object> row : due) {
            UUID id = (UUID) row.get("id");
            UUID practiceId = (UUID) row.get("practice_id");
            UUID patientId = (UUID) row.get("patient_id");
            UUID appointmentId = (UUID) row.get("appointment_id");
            String phone = (String) row.get("recipient_phone");
            String language = (String) row.get("language");
            int attempts = ((Number) row.get("attempts")).intValue() + 1;
            try {
                tenancy.runAs(practiceId, () -> tx.executeWithoutResult(status -> {
                    if (registrationService.isRegistrationCompleted(practiceId, patientId)) {
                        jdbc.update("UPDATE registration_link_retries SET completed_at = NOW(), attempts = ? WHERE id = ?", attempts, id);
                        return;
                    }
                    String link = registrationService.issuePersonalRegistrationUrl(practiceId, patientId);
                    messages.enqueue(OutgoingMessage.builder()
                            .practiceId(practiceId)
                            .channel(MessageChannel.WHATSAPP)
                            .purpose(MessagePurpose.REGISTRATION_INVITE)
                            .patientId(patientId)
                            .recipient(phone)
                            .language(language)
                            .variables(Map.of("link", link))
                            .relatedType("APPOINTMENT")
                            .relatedId(appointmentId)
                            .dedupeKey("reg-retry:" + id)
                            .skipConsentCheck(true)
                            .build());
                    jdbc.update("UPDATE registration_link_retries SET completed_at = NOW(), attempts = ?, last_error = NULL WHERE id = ?",
                            attempts, id);
                }));
                processed++;
            } catch (RuntimeException e) {
                log.warn("Background registration link retry {} failed (attempt {}): {}", id, attempts, e.getMessage());
                long backoffMinutes = Math.min(60L, 1L << Math.min(attempts, 6));
                jdbc.update("""
                        UPDATE registration_link_retries
                        SET attempts = ?, next_attempt_at = ?, last_error = ?
                        WHERE id = ?
                        """, attempts, OffsetDateTime.now().plusMinutes(backoffMinutes), e.getMessage(), id);
            }
        }
        return processed;
    }
}
