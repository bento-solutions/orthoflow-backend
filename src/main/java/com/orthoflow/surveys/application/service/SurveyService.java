package com.orthoflow.surveys.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLink;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.settings.application.service.PracticeProfileService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Satisfaction surveys. The patient gets a single-use link after a visit and
 * answers with a rating, an optional comment and "please call me back". A low
 * rating or a call-back request reaches the staff the same day, because that is
 * when it can still be put right.
 */
@Service
@RequiredArgsConstructor
public class SurveyService {

    private static final int LOW_RATING = 2;

    @io.swagger.v3.oas.annotations.media.Schema(name = "SurveyPublicInfo")
    public record PublicInfo(String clinicName, String practitionerName) {
    }

    public record Answer(@Min(1) @Max(5) int rating, @Size(max = 2000) String comment, boolean callMe,
                         /** A hidden field a person never fills; a bot does. */ String website) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "SurveyRow")
    public record Row(UUID id, UUID appointmentId, UUID patientId, String patientName, String practitionerName, Integer rating, String comment,
                      boolean callMe, OffsetDateTime submittedAt, OffsetDateTime handledAt) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "SurveySummary")
    public record Summary(long responses, Double average, long[] distribution, long callBacksPending) {
    }

    public record Report(Summary summary, List<Row> rows) {
    }

    private final JdbcTemplate jdbc;
    private final PublicLinkService publicLinks;
    private final PracticeProfileService profiles;
    private final StaffNotifier notifier;
    private final PracticeZone practiceZone;
    private final LiveEventPublisher liveEvents;

    @Transactional(readOnly = true)
    public PublicInfo info(String token) {
        PublicLink link = publicLinks.resolve(token, PublicLinkPurpose.SURVEY);
        String practitioner = jdbc.query("SELECT p.display_name FROM satisfaction_surveys s LEFT JOIN practitioners p ON p.id = s.practitioner_id WHERE s.appointment_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, link.getSubjectId());
        return new PublicInfo(profiles.require(link.getPracticeId()).getName(), practitioner);
    }

    @Transactional
    public void answer(String token, Answer a) {
        PublicLink link = publicLinks.resolve(token, PublicLinkPurpose.SURVEY);
        if (a.website() != null && !a.website().isBlank()) {
            return;
        }
        if (!publicLinks.consume(link.getId())) {
            throw new NotFoundException("This link is not valid");
        }
        int updated = jdbc.update("UPDATE satisfaction_surveys SET rating = ?, comment = ?, call_me = ?, submitted_at = NOW() WHERE appointment_id = ? AND submitted_at IS NULL",
                a.rating(), a.comment() == null || a.comment().isBlank() ? null : a.comment().trim(), a.callMe(), link.getSubjectId());
        if (updated == 0) {
            throw new NotFoundException("This link is not valid");
        }
        UUID practiceId = link.getPracticeId();
        if (a.callMe() || a.rating() <= LOW_RATING) {
            notifier.toPermission(practiceId, Permission.SURVEYS_VIEW, MessagePurpose.GENERIC,
                    a.callMe() ? "Un patient demande à être rappelé" : "Avis peu satisfait (" + a.rating() + "/5)",
                    a.comment() == null || a.comment().isBlank() ? "Voir les avis patients." : a.comment().trim(), "SURVEY", (UUID) link.getSubjectId());
        }
        liveEvents.publish(practiceId, "survey", link.getSubjectId());
    }

    @Transactional(readOnly = true)
    public Report report(UUID practiceId, LocalDate from, LocalDate to, Integer maxRating, boolean callMeOnly, boolean unhandledOnly) {
        var zone = practiceZone.of(practiceId);
        OffsetDateTime start = from.atStartOfDay(zone).toOffsetDateTime();
        OffsetDateTime end = to.plusDays(1).atStartOfDay(zone).toOffsetDateTime();
        List<Row> rows = jdbc.query("""
                SELECT s.id, s.appointment_id, s.patient_id, pa.first_name || ' ' || pa.last_name AS patient, pr.display_name AS practitioner,
                       s.rating, s.comment, s.call_me, s.submitted_at, s.handled_at
                FROM satisfaction_surveys s JOIN patients pa ON pa.id = s.patient_id LEFT JOIN practitioners pr ON pr.id = s.practitioner_id
                WHERE s.practice_id = ? AND s.submitted_at >= ? AND s.submitted_at < ?
                  AND (CAST(? AS INT) IS NULL OR s.rating <= CAST(? AS INT)) AND (? = false OR s.call_me = true)
                  AND (? = false OR (s.handled_at IS NULL AND s.call_me = true))
                ORDER BY s.submitted_at DESC LIMIT 500
                """, (rs, i) -> new Row(rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class), rs.getObject("patient_id", UUID.class),
                rs.getString("patient"), rs.getString("practitioner"), rs.getObject("rating") == null ? null : rs.getInt("rating"), rs.getString("comment"),
                rs.getBoolean("call_me"), rs.getObject("submitted_at", OffsetDateTime.class), rs.getObject("handled_at", OffsetDateTime.class)),
                practiceId, start, end, maxRating, maxRating, callMeOnly, unhandledOnly);
        long[] distribution = new long[5];
        double sum = 0;
        for (Row r : rows) {
            if (r.rating() != null) {
                distribution[r.rating() - 1]++;
                sum += r.rating();
            }
        }
        long answered = rows.stream().filter(r -> r.rating() != null).count();
        long pending = rows.stream().filter(r -> r.callMe() && r.handledAt() == null).count();
        return new Report(new Summary(answered, answered == 0 ? null : Math.round(sum / answered * 100) / 100.0, distribution, pending), rows);
    }

    @Transactional
    public void markHandled(UUID practiceId, UUID actorId, UUID id) {
        int n = jdbc.update("UPDATE satisfaction_surveys SET handled_at = NOW(), handled_by = ? WHERE id = ? AND practice_id = ? AND handled_at IS NULL", actorId, id, practiceId);
        if (n == 0 && jdbc.queryForObject("SELECT count(*) FROM satisfaction_surveys WHERE id = ? AND practice_id = ?", Integer.class, id, practiceId) == 0) {
            throw new NotFoundException("Survey response not found");
        }
    }
}
