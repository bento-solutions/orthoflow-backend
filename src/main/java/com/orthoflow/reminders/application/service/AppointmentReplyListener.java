package com.orthoflow.reminders.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.application.port.InboundMessageListener;
import com.orthoflow.messaging.application.service.MessageService;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.service.AppointmentService;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A patient answers a reminder: "1" confirms, "2" cancels. The reply is matched to
 * the appointment the reminder was about — the most recent reminder sent to that
 * patient — never to "their next appointment", so a reply that arrives days late
 * cannot confirm or cancel the wrong visit. Anything else is left for staff.
 */
@Component
@RequiredArgsConstructor
public class AppointmentReplyListener implements InboundMessageListener {

    private static final Logger log = LoggerFactory.getLogger(AppointmentReplyListener.class);
    private static final Set<String> YES = Set.of("1", "oui", "yes", "ok", "نعم", "confirme", "confirmer", "ouii");
    private static final Set<String> NO = Set.of("2", "non", "no", "لا", "annuler", "annule");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final AppointmentJpaRepository appointments;
    private final AppointmentService appointmentService;
    private final PatientLookup patientLookup;
    private final MessageService messages;
    private final StaffNotifier notifier;
    private final PracticeZone practiceZone;

    @Override
    @Transactional
    public boolean onInbound(UUID practiceId, UUID patientId, String phoneDigits, String body) {
        Boolean confirm = interpret(body);
        if (confirm == null) {
            return false;
        }
        Optional<UUID> remindedAppointment = jdbc.queryForList("""
                SELECT related_id FROM message_outbox WHERE patient_id = ? AND purpose = 'APPOINTMENT_REMINDER'
                  AND related_type = 'APPOINTMENT' AND status IN ('SENT', 'DELIVERED', 'READ') AND created_at > now() - interval '4 days'
                ORDER BY created_at DESC LIMIT 1
                """, UUID.class, patientId).stream().findFirst();
        if (remindedAppointment.isEmpty()) {
            return false;
        }
        Appointment a = appointments.findById(remindedAppointment.get()).orElse(null);
        if (a == null || !a.getStatus().isUpcoming() || a.getDateTime().isBefore(OffsetDateTime.now())) {
            return false;
        }
        AppointmentRequest change = new AppointmentRequest();
        change.setStatus(confirm ? AppointmentStatus.CONFIRMED : AppointmentStatus.CANCELLED);
        appointmentService.updateAppointment(a.getId(), change);

        PatientSummary patient = patientLookup.findSummary(patientId).orElse(null);
        var zone = practiceZone.of(practiceId);
        Map<String, String> when = Map.of("date", DAY.format(a.getDateTime().atZoneSameInstant(zone)), "time", TIME.format(a.getDateTime().atZoneSameInstant(zone)));
        if (patient != null) {
            messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(MessageChannel.WHATSAPP)
                    .purpose(confirm ? MessagePurpose.APPOINTMENT_CONFIRMED : MessagePurpose.APPOINTMENT_CANCELLED).patientId(patientId)
                    .recipient(phoneDigits).language(patient.preferredLanguage()).variables(when).relatedType("APPOINTMENT")
                    .relatedId(a.getId()).skipConsentCheck(true).build());
        }
        if (!confirm) {
            notifier.toPermission(practiceId, Permission.AGENDA_MANAGE, MessagePurpose.APPOINTMENT_CANCELLED, "Rendez-vous annulé par le patient",
                    (patient == null ? "Un patient" : patient.fullName()) + " a annulé son rendez-vous du " + when.get("date") + " à " + when.get("time") + " (réponse WhatsApp).",
                    "APPOINTMENT", a.getId());
        }
        log.info("Patient {} {} appointment {} by reply", patientId, confirm ? "confirmed" : "cancelled", a.getId());
        return true;
    }

    /** True for a yes, false for a no, null for anything else. Accent- and case-insensitive. */
    static Boolean interpret(String body) {
        if (body == null) {
            return null;
        }
        String text = Normalizer.normalize(body.trim().toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .replaceAll("[\\s.!,]+$", "");
        if (YES.contains(text)) {
            return Boolean.TRUE;
        }
        return NO.contains(text) ? Boolean.FALSE : null;
    }
}
