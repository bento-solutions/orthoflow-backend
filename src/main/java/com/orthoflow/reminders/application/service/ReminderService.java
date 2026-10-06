package com.orthoflow.reminders.application.service;

import com.orthoflow.billing.domain.model.PaymentPlanInstalment;
import com.orthoflow.billing.infrastructure.adapter.persistence.PaymentPlanJpaRepository;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.infrastructure.Cells;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.application.service.MessageService;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The messages the clinic sends without being asked: tomorrow's appointments,
 * instalments falling due, recall notices, a survey after a visit. Each goes
 * through the outbox in the patient's own language, only to a patient who agreed,
 * and carries a dedupe key, so running a job twice — a restart, an overlapping
 * schedule — never sends anything twice.
 */
@Service
@RequiredArgsConstructor
public class ReminderService {

    private static final Logger log = LoggerFactory.getLogger(ReminderService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final AppointmentJpaRepository appointments;
    private final PaymentPlanJpaRepository plans;
    private final PatientLookup patientLookup;
    private final ChannelPicker channelPicker;
    private final MessageService messages;
    private final PublicLinkService publicLinks;
    private final PracticeZone practiceZone;
    private final JdbcTemplate jdbc;

    @Value("${app.frontend-url:http://localhost:4200}")
    private String frontendUrl;

    /** Reminders for every booked visit on {@code day} that has not had one. Returns how many were queued. */
    @Transactional
    public int queueAppointmentReminders(UUID practiceId, LocalDate day) {
        ZoneId zone = practiceZone.of(practiceId);
        OffsetDateTime from = day.atStartOfDay(zone).toOffsetDateTime();
        List<Appointment> visits = appointments.inStatuses(practiceId, List.of(AppointmentStatus.SCHEDULED, AppointmentStatus.CONFIRMED), from, from.plusDays(1));
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(visits.stream().map(Appointment::getPatientId).distinct().toList());
        int queued = 0;
        for (Appointment a : visits) {
            PatientSummary p = patients.get(a.getPatientId());
            if (p == null) {
                continue;
            }
            Optional<MessageChannel> channel = channelPicker.pick(p);
            if (channel.isEmpty()) {
                continue;
            }
            String key = "reminder:" + a.getId() + ":" + day;
            if (messages.alreadyQueued(key)) {
                continue;
            }
            messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(channel.get())
                    .purpose(MessagePurpose.APPOINTMENT_REMINDER).patientId(p.id()).language(p.preferredLanguage())
                    .variables(Map.of("date", DAY.format(a.getDateTime().atZoneSameInstant(zone)), "time", TIME.format(a.getDateTime().atZoneSameInstant(zone))))
                    .relatedType("APPOINTMENT").relatedId(a.getId()).dedupeKey(key).build());
            queued++;
        }
        return queued;
    }

    /** A notice for every instalment due in {@code daysBefore} days (and the ones due today). */
    @Transactional
    public int queueInstalmentReminders(UUID practiceId, LocalDate today, int daysBefore) {
        LocalDate target = today.plusDays(daysBefore);
        List<PaymentPlanInstalment> due = plans.due(practiceId, target).stream()
                .filter(i -> !i.getDueDate().isBefore(today)).collect(Collectors.toList());
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(due.stream().map(i -> i.getPlan().getPatientId()).distinct().toList());
        int queued = 0;
        for (PaymentPlanInstalment i : due) {
            PatientSummary p = patients.get(i.getPlan().getPatientId());
            Optional<MessageChannel> channel = p == null ? Optional.empty() : channelPicker.pick(p);
            String key = "instalment:" + i.getId() + ":" + i.getDueDate();
            if (channel.isEmpty() || messages.alreadyQueued(key)) {
                continue;
            }
            messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(channel.get()).purpose(MessagePurpose.INSTALMENT_REMINDER)
                    .patientId(p.id()).language(p.preferredLanguage())
                    .variables(Map.of("date", DAY.format(i.getDueDate()), "amount", Cells.money(i.remaining()), "currency", "MAD"))
                    .relatedType("INSTALMENT").relatedId(i.getId()).dedupeKey(key).build());
            queued++;
        }
        return queued;
    }

    /** "Come and see us": one per patient per month, to the patients staff chose from a recall list. */
    @Transactional
    public int sendRecallReminders(UUID practiceId, UUID actorId, List<UUID> patientIds) {
        String month = LocalDate.now(practiceZone.of(practiceId)).toString().substring(0, 7);
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(patientIds);
        int queued = 0;
        for (UUID id : patientIds) {
            PatientSummary p = patients.get(id);
            Optional<MessageChannel> channel = p == null ? Optional.empty() : channelPicker.pick(p);
            String key = "recall:" + id + ":" + month;
            if (channel.isPresent() && !messages.alreadyQueued(key)) {
                messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(channel.get())
                        .purpose(MessagePurpose.RECALL).patientId(id).language(p.preferredLanguage()).createdBy(actorId)
                        .relatedType("PATIENT").relatedId(id).dedupeKey(key).build());
                queued++;
            }
        }
        return queued;
    }

    /**
     * Asks for feedback on visits finished {@code delayHours} ago or more, once each.
     * The survey row and its single-use link are created only when there is a way and
     * a right to reach the patient, so an unreachable patient leaves nothing behind.
     */
    @Transactional
    public int requestSurveys(UUID practiceId, OffsetDateTime now, int delayHours) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT a.id, a.patient_id, a.practitioner_id FROM appointments a
                WHERE a.practice_id = ? AND a.status = 'COMPLETED' AND a.finished_at IS NOT NULL
                  AND a.finished_at <= ? AND a.finished_at > ? AND NOT EXISTS (SELECT 1 FROM satisfaction_surveys s WHERE s.appointment_id = a.id)
                ORDER BY a.finished_at LIMIT 200
                """, practiceId, now.minusHours(delayHours), now.minusHours(delayHours).minusHours(24));
        int queued = 0;
        for (Map<String, Object> row : rows) {
            UUID appointmentId = (UUID) row.get("id");
            UUID patientId = (UUID) row.get("patient_id");
            PatientSummary p = patientLookup.findSummary(patientId).orElse(null);
            Optional<MessageChannel> channel = p == null ? Optional.empty() : channelPicker.pick(p);
            if (channel.isEmpty()) {
                continue;
            }
            var link = publicLinks.issue(practiceId, PublicLinkPurpose.SURVEY, "APPOINTMENT", appointmentId, Duration.ofDays(14), 1, null);
            jdbc.update("INSERT INTO satisfaction_surveys (id, practice_id, appointment_id, patient_id, practitioner_id, link_id) VALUES (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), practiceId, appointmentId, patientId, row.get("practitioner_id"), link.id());
            messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(channel.get()).purpose(MessagePurpose.SURVEY_REQUEST)
                    .patientId(patientId).language(p.preferredLanguage()).variables(Map.of("link", frontendUrl + "/public/survey/" + link.token()))
                    .relatedType("APPOINTMENT").relatedId(appointmentId).dedupeKey("survey:" + appointmentId).build());
            queued++;
        }
        if (queued > 0) {
            log.info("Requested {} satisfaction survey(s) for practice {}", queued, practiceId);
        }
        return queued;
    }
}
