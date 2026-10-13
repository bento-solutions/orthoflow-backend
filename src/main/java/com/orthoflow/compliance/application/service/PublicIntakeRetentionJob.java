package com.orthoflow.compliance.application.service;

import com.orthoflow.messaging.infrastructure.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Deletes what strangers put through the public pages once nobody needs it (Law 09-08: personal data
 * is kept no longer than the purpose needs it). A request the clinic declined, a request that expired,
 * a registration form it rejected, or one nobody ever looked at is a name, a phone number and often a
 * date of birth and a CIN from a person who never became a patient.
 *
 * <p>What it leaves: anything that became a patient (a confirmed request, an approved registration)
 * lives and dies with that patient's record. The periods are policy, to be set against the clinic's
 * CNDP declaration; the defaults are a conservative starting point, not a legal requirement quoted
 * from a text. A period of 0 or less keeps that kind forever.
 */
@Component
public class PublicIntakeRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(PublicIntakeRetentionJob.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MessagingProperties messaging;
    private final int declinedRequestDays;
    private final int rejectedRegistrationDays;
    private final int unansweredRegistrationDays;
    private final int landingContactDays;
    private final int surveyCommentDays;

    public PublicIntakeRetentionJob(JdbcTemplate jdbc, TransactionTemplate tx, MessagingProperties messaging,
                                    @Value("${orthoflow.retention.declined-booking-days:30}") int declinedRequestDays,
                                    @Value("${orthoflow.retention.rejected-registration-days:30}") int rejectedRegistrationDays,
                                    @Value("${orthoflow.retention.unanswered-registration-days:90}") int unansweredRegistrationDays,
                                    @Value("${orthoflow.retention.landing-contact-days:365}") int landingContactDays,
                                    @Value("${orthoflow.retention.survey-comment-days:730}") int surveyCommentDays) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.messaging = messaging;
        this.declinedRequestDays = declinedRequestDays;
        this.rejectedRegistrationDays = rejectedRegistrationDays;
        this.unansweredRegistrationDays = unansweredRegistrationDays;
        this.landingContactDays = landingContactDays;
        this.surveyCommentDays = surveyCommentDays;
    }

    @Scheduled(cron = "${orthoflow.retention.cron:0 20 3 * * *}")
    public void run() {
        int removed = purge();
        if (removed > 0) {
            log.info("Public-intake retention removed or blanked {} row(s)", removed);
        }
    }

    /** Returns how many rows were deleted or blanked. */
    public int purge() {
        Integer n = tx.execute(s -> {
            int total = 0;
            if (declinedRequestDays > 0) {
                total += jdbc.update("DELETE FROM booking_requests WHERE status IN ('DECLINED', 'EXPIRED') AND patient_id IS NULL "
                        + "AND COALESCE(decided_at, created_at) < now() - make_interval(days => ?)", declinedRequestDays);
            }
            if (rejectedRegistrationDays > 0) {
                total += jdbc.update("DELETE FROM pending_patients WHERE status = 'REJECTED' AND patient_id IS NULL "
                        + "AND COALESCE(decided_at, created_at) < now() - make_interval(days => ?)", rejectedRegistrationDays);
            }
            if (unansweredRegistrationDays > 0) {
                total += jdbc.update("DELETE FROM pending_patients WHERE status = 'PENDING' AND patient_id IS NULL "
                        + "AND created_at < now() - make_interval(days => ?)", unansweredRegistrationDays);
            }
            if (landingContactDays > 0) {
                // A number that never became a patient: its only use was to let a reply through.
                total += jdbc.update("DELETE FROM landing_page_contacts c WHERE c.first_seen_at < now() - make_interval(days => ?) AND NOT EXISTS "
                        + "(SELECT 1 FROM patients p WHERE p.practice_id = c.practice_id AND phone_digits(p.phone) = c.phone_key)", landingContactDays);
            }
            int messageDays = messaging.getRetentionDays();
            if (messageDays > 0) {
                // What a person wrote to the clinic's number is purged on the schedule of what the clinic wrote to them.
                total += jdbc.update("UPDATE message_events SET body = NULL WHERE body IS NOT NULL AND occurred_at < now() - make_interval(days => ?)", messageDays);
            }
            if (surveyCommentDays > 0) {
                total += jdbc.update("UPDATE satisfaction_surveys SET comment = NULL WHERE comment IS NOT NULL "
                        + "AND COALESCE(submitted_at, requested_at) < now() - make_interval(days => ?)", surveyCommentDays);
            }
            return total;
        });
        return n == null ? 0 : n;
    }
}
