package com.orthoflow.reminders.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.application.port.InboundMessageListener;
import com.orthoflow.messaging.application.service.MessageService;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
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
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Parses patient replies to the 20:00 reminder:
 * 1 = confirmed on time, 2 = confirmed but late, 3 = cannot come (cancel).
 * Supports both quick-reply digits (1/2/3) and common French/Arabic/English free text,
 * matches the most recent reminded appointment, updates the appointment & reply fields,
 * and flags late/cancelled answers for the clinic staff.
 */
@Component
@RequiredArgsConstructor
public class AppointmentReplyListener implements InboundMessageListener {

    public enum ReplyChoice {
        CONFIRMED_ON_TIME,
        CONFIRMED_LATE,
        CANNOT_COME
    }

    private static final Logger log = LoggerFactory.getLogger(AppointmentReplyListener.class);
    private static final Pattern QUICK_ONE = Pattern.compile("^1(?:\\s*[.):\\-]+.*|\\s+.*)?$");
    private static final Pattern QUICK_TWO = Pattern.compile("^2(?:\\s*[.):\\-]+.*|\\s+.*)?$");
    private static final Pattern QUICK_THREE = Pattern.compile("^3(?:\\s*[.):\\-]+.*|\\s+.*)?$");
    private static final Set<String> EXACT_YES = Set.of("oui", "ouii", "yes", "ok", "confirme", "confirmer", "نعم");
    private static final Set<String> EXACT_NO = Set.of("non", "no", "annuler", "annule", "cancel", "لا");
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
        ReplyChoice choice = interpretChoice(body);
        if (choice == null) {
            return false;
        }
        Optional<UUID> remindedAppointment = jdbc.queryForList("""
                SELECT related_id FROM message_outbox WHERE patient_id = ? AND purpose = 'APPOINTMENT_REMINDER'
                  AND related_type = 'APPOINTMENT' AND status IN ('SENT', 'DELIVERED', 'READ', 'QUEUED')
                  AND created_at > now() - interval '4 days'
                ORDER BY created_at DESC LIMIT 1
                """, UUID.class, patientId).stream().findFirst();
        if (remindedAppointment.isEmpty()) {
            return false;
        }
        Appointment a = appointments.findById(remindedAppointment.get()).orElse(null);
        if (a == null || !a.getStatus().isUpcoming() || a.getDateTime().isBefore(OffsetDateTime.now())) {
            return false;
        }
        AppointmentStatus nextStatus = switch (choice) {
            case CONFIRMED_ON_TIME -> AppointmentStatus.CONFIRMED;
            case CONFIRMED_LATE -> AppointmentStatus.LATE;
            case CANNOT_COME -> AppointmentStatus.CANCELLED;
        };
        AppointmentRequest change = new AppointmentRequest();
        change.setStatus(nextStatus);
        appointmentService.updateAppointment(a.getId(), change);

        String trimmedNote = body == null ? null : body.trim();
        if (trimmedNote != null && trimmedNote.length() > 300) {
            trimmedNote = trimmedNote.substring(0, 300);
        }
        final String replyNote = trimmedNote;
        appointments.findById(a.getId()).ifPresent(updated -> {
            updated.setReplyStatus(choice.name());
            updated.setReplyReceivedAt(OffsetDateTime.now());
            updated.setReplyNote(replyNote);
            appointments.save(updated);
        });

        PatientSummary patient = patientLookup.findSummary(patientId).orElse(null);
        var zone = practiceZone.of(practiceId);
        Map<String, String> when = Map.of(
                "date", DAY.format(a.getDateTime().atZoneSameInstant(zone)),
                "time", TIME.format(a.getDateTime().atZoneSameInstant(zone)));
        if (patient != null) {
            messages.enqueue(OutgoingMessage.builder()
                    .practiceId(practiceId)
                    .channel(MessageChannel.WHATSAPP)
                    .purpose(choice == ReplyChoice.CANNOT_COME
                            ? MessagePurpose.APPOINTMENT_CANCELLED
                            : MessagePurpose.APPOINTMENT_CONFIRMED)
                    .patientId(patientId)
                    .recipient(phoneDigits)
                    .language(patient.preferredLanguage())
                    .variables(when)
                    .relatedType("APPOINTMENT")
                    .relatedId(a.getId())
                    .skipConsentCheck(true)
                    .build());
        }
        String displayName = patient == null ? "Un patient" : patient.fullName();
        if (choice == ReplyChoice.CANNOT_COME) {
            notifier.toPermission(practiceId, Permission.AGENDA_MANAGE, MessagePurpose.APPOINTMENT_CANCELLED,
                    "Rendez-vous annulé par le patient",
                    displayName + " ne peut pas venir le " + when.get("date") + " à " + when.get("time") + " (réponse WhatsApp).",
                    "APPOINTMENT", a.getId());
        } else if (choice == ReplyChoice.CONFIRMED_LATE) {
            notifier.toPermission(practiceId, Permission.AGENDA_MANAGE, MessagePurpose.APPOINTMENT_CONFIRMED,
                    "Patient en retard signalé",
                    displayName + " a confirmé son rendez-vous du " + when.get("date") + " à " + when.get("time") + " avec du retard (réponse WhatsApp).",
                    "APPOINTMENT", a.getId());
        }
        log.info("Patient {} replied {} for appointment {}", patientId, choice, a.getId());
        return true;
    }

    /**
     * Parses reminder replies: 1 = CONFIRMED_ON_TIME, 2 = CONFIRMED_LATE, 3 = CANNOT_COME,
     * plus French, Arabic and English free-text expressions. Ambiguous replies return null
     * so staff can handle them in the WhatsApp inbox.
     */
    public static ReplyChoice interpretChoice(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String rawTrimmed = body.trim();
        if (QUICK_ONE.matcher(rawTrimmed).matches()) {
            return ReplyChoice.CONFIRMED_ON_TIME;
        }
        if (QUICK_TWO.matcher(rawTrimmed).matches()) {
            return ReplyChoice.CONFIRMED_LATE;
        }
        if (QUICK_THREE.matcher(rawTrimmed).matches()) {
            return ReplyChoice.CANNOT_COME;
        }

        String text = Normalizer.normalize(rawTrimmed.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[\\s.!,؟]+$", "")
                .trim();
        if (text.isEmpty()) {
            return null;
        }
        // Ambiguous rescheduling / conditional requests belong in the manual staff inbox.
        if (text.contains("mais") || text.contains("but") || text.contains("plutot")
                || text.contains("decaler") || text.contains("changer") || text.contains("reschedul")) {
            return null;
        }
        if (EXACT_YES.contains(text)) {
            return ReplyChoice.CONFIRMED_ON_TIME;
        }
        if (EXACT_NO.contains(text)) {
            return ReplyChoice.CANNOT_COME;
        }
        if (containsAny(text, "retard", "running late", "be late", "late", "متاخر", "تاخر", "ساتاخر", "اتأخر")) {
            return ReplyChoice.CONFIRMED_LATE;
        }
        if (containsAny(text, "annul", "cancel", "ne peux pas", "peux pas", "pas venir", "impossible",
                "cannot come", "can't come", "cant come", "won't make", "wont make",
                "لا استطيع", "تعذر", "لن احضر", "الغاء")) {
            return ReplyChoice.CANNOT_COME;
        }
        if (containsAny(text, "a l'heure", "a temps", "on time", "confirme", "confirmer",
                "في الوقت", "في الموعد", "ساحضر")) {
            return ReplyChoice.CONFIRMED_ON_TIME;
        }
        return null;
    }

    private static boolean containsAny(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) {
                return true;
            }
        }
        return false;
    }
}
