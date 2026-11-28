package com.orthoflow.booking.application.service;

import com.orthoflow.booking.application.dto.BookingDtos.*;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.application.service.ConsentService;
import com.orthoflow.messaging.application.service.DefaultTemplates;
import com.orthoflow.messaging.application.service.LandingPageContacts;
import com.orthoflow.messaging.application.service.MessageService;
import com.orthoflow.messaging.application.service.PhoneNumbers;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import com.orthoflow.patient.application.port.PatientRegistrar;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.registration.application.service.RegistrationService;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.application.service.AppointmentService;
import com.orthoflow.scheduling.application.service.SlotFinder;
import com.orthoflow.scheduling.domain.model.AppointmentType;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentTypeJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Atomic public appointment booking:
 * <ol>
 *   <li>Verify honeypot, CAPTCHA, E.164 phone, and live slot availability under an advisory lock.</li>
 *   <li>Inside one transaction: match/create the patient, then create the appointment atomically.</li>
 *   <li>Retrieve a personal registration link if incomplete (and schedule a background retry on failure
 *       without rolling back the committed booking).</li>
 *   <li>Queue the WhatsApp confirmation asynchronously via the outbox.</li>
 * </ol>
 *
 * <p>Privacy: for existing patients matched by phone, the registration link is NEVER returned
 * to the browser; it is sent only to that patient's WhatsApp number. Registration links are never logged.
 */
@Service
@RequiredArgsConstructor
public class DirectBookingService {

    private static final Logger log = LoggerFactory.getLogger(DirectBookingService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private record BookedCore(
            UUID practiceId,
            UUID patientId,
            boolean existingPatient,
            AppointmentResponse appointment,
            String doctorName,
            ZoneId zone) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final BookingService bookingService;
    private final PublicBookingTargetResolver targetResolver;
    private final PublicLinkService publicLinks;
    private final CaptchaVerifier captchaVerifier;
    private final AppointmentTypeJpaRepository types;
    private final PractitionerService practitionerService;
    private final SlotFinder slotFinder;
    private final AppointmentService appointmentService;
    private final PatientRegistrar patientRegistrar;
    private final RegistrationService registrationService;
    private final RegistrationLinkRetryService retryService;
    private final ConsentService consent;
    private final MessageService messages;
    private final LandingPageContacts landingPageContacts;
    private final MessagingProperties messagingProperties;
    private final PracticeZone practiceZone;
    private final LetterheadProvider letterheadProvider;
    private final LiveEventPublisher liveEvents;

    public DirectBookResponse bookForConfiguredDoctor(DirectBookRequest request, String apiKeyHeader, String clientIp) {
        if (isHoneypotTripped(request.website())) {
            return honeypotResponse(request);
        }
        captchaVerifier.verify(request.captchaToken(), apiKeyHeader, clientIp);
        var target = targetResolver.resolve()
                .orElseThrow(() -> new NotFoundException("Online booking doctor account is not configured"));
        return executeBooking(target.practiceId(), target.practitionerId(), target.practitionerName(), request);
    }

    public DirectBookResponse bookWithToken(String token, DirectBookRequest request, String apiKeyHeader, String clientIp) {
        if (isHoneypotTripped(request.website())) {
            return honeypotResponse(request);
        }
        captchaVerifier.verify(request.captchaToken(), apiKeyHeader, clientIp);
        var link = publicLinks.resolve(token, PublicLinkPurpose.BOOKING);
        UUID practiceId = link.getPracticeId();
        Settings settings = bookingService.settings(practiceId);
        if (!settings.enabled()) {
            throw new NotFoundException("This link is not valid");
        }
        UUID practitionerId = request.practitionerId();
        String practitionerName = null;
        if (practitionerId != null) {
            practitionerName = practitionerService.require(practiceId, practitionerId).getDisplayName();
        }
        return executeBooking(practiceId, practitionerId, practitionerName, request);
    }

    private DirectBookResponse executeBooking(UUID practiceId, UUID requestedPractitionerId, String requestedDoctorName,
                                              DirectBookRequest request) {
        String cleanName = sanitizeText(request.fullName(), 255);
        if (cleanName == null) {
            throw new ValidationException("Please enter your full name.");
        }
        String cleanNotes = sanitizeText(request.notes(), 1000);
        String e164 = PhoneNumbers.normalizeE164(
                request.phone(), request.countryCode(), messagingProperties.getWhatsapp().getDefaultCountryCode());
        if (e164 == null) {
            throw new ValidationException("Please enter a valid WhatsApp phone number.");
        }
        String lang = request.language() == null ? "fr" : request.language();

        BookedCore core = tx.execute(status -> commitPatientAndAppointment(
                practiceId, requestedPractitionerId, requestedDoctorName, request, cleanName, cleanNotes, e164, lang));
        if (core == null) {
            throw new IllegalStateException("Booking transaction produced no result");
        }

        // Step (c): Retrieve personal registration link only after patient + appointment commit.
        boolean alreadyCompleted = registrationService.isRegistrationCompleted(core.practiceId(), core.patientId());
        String personalRegistrationUrl = null;
        if (!alreadyCompleted) {
            try {
                personalRegistrationUrl = registrationService.issuePersonalRegistrationUrl(core.practiceId(), core.patientId());
            } catch (RuntimeException ex) {
                log.warn("Failed to issue registration link for appointment {}: {}", core.appointment().getId(), ex.getMessage());
                retryService.scheduleRetry(core.practiceId(), core.patientId(), core.appointment().getId(), e164, lang);
            }
        }

        // Step (d): Queue WhatsApp confirmation asynchronously via outbox (never block the HTTP response).
        var localStart = request.startsAt().atZoneSameInstant(core.zone());
        String registrationSection = (!alreadyCompleted && personalRegistrationUrl != null)
                ? DefaultTemplates.registrationSnippet(lang, personalRegistrationUrl)
                : "";
        messages.enqueue(OutgoingMessage.builder()
                .practiceId(core.practiceId())
                .channel(MessageChannel.WHATSAPP)
                .purpose(MessagePurpose.BOOKING_CONFIRMED)
                .patientId(core.patientId())
                .recipient(e164)
                .language(lang)
                .variables(Map.of(
                        "date", DAY.format(localStart),
                        "time", TIME.format(localStart),
                        "registrationSection", registrationSection))
                .relatedType("APPOINTMENT")
                .relatedId(core.appointment().getId())
                .dedupeKey("booking-confirm:" + core.appointment().getId())
                .skipConsentCheck(true)
                .build());
        liveEvents.publish(core.practiceId(), "appointment", core.appointment().getId());

        // Privacy: never expose an existing patient's registration link to the browser.
        String responseRegistrationUrl = (!core.existingPatient() && !alreadyCompleted) ? personalRegistrationUrl : null;
        Letterhead letterhead = letterheadProvider.forPractice(core.practiceId());
        return new DirectBookResponse(
                reference(core.appointment().getId()),
                core.appointment().getId(),
                core.appointment().getDateTime(),
                core.appointment().getDurationMinutes(),
                core.doctorName(),
                letterhead.name(),
                letterhead.addressLine(),
                core.appointment().getType(),
                responseRegistrationUrl,
                alreadyCompleted,
                core.existingPatient(),
                !alreadyCompleted);
    }

    private BookedCore commitPatientAndAppointment(UUID practiceId, UUID requestedPractitionerId, String requestedDoctorName,
                                                   DirectBookRequest request, String cleanName, String cleanNotes,
                                                   String e164, String lang) {
        Settings settings = bookingService.settings(practiceId);
        AppointmentType type = types.findByIdAndPracticeId(request.appointmentTypeId(), practiceId)
                .filter(t -> t.isActive() && t.isBookableOnline())
                .orElseThrow(() -> new ValidationException("That kind of visit cannot be booked online"));

        // Serialize slot checks & writes per clinic to prevent TOCTOU races alongside DB exclusion constraints.
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtext(?::text))", Object.class, "booking:" + practiceId);

        ZoneId zone = practiceZone.of(practiceId);
        OffsetDateTime now = OffsetDateTime.now();
        if (request.startsAt().isBefore(now.plusHours(settings.leadTimeHours()))
                || request.startsAt().isAfter(now.plusDays(settings.maxDaysAhead()))) {
            throw new ValidationException("That time cannot be booked online");
        }
        LocalDate day = request.startsAt().atZoneSameInstant(zone).toLocalDate();
        List<SlotFinder.Hold> dayHolds = holdsForDay(practiceId, day, zone);
        if (!slotFinder.isFree(practiceId, request.startsAt(), type.getDefaultDurationMinutes(), requestedPractitionerId, dayHolds)) {
            throw new ConflictException("That time is no longer available. Please pick another.");
        }

        UUID practitionerId = requestedPractitionerId;
        String doctorName = requestedDoctorName;
        if (practitionerId == null) {
            var activePractitioners = practitionerService.list(practiceId, false);
            for (var p : activePractitioners) {
                if (slotFinder.isFree(practiceId, request.startsAt(), type.getDefaultDurationMinutes(), p.id(), dayHolds)) {
                    practitionerId = p.id();
                    doctorName = p.displayName();
                    break;
                }
            }
            if (practitionerId == null && !activePractitioners.isEmpty()) {
                throw new ConflictException("That time is no longer available. Please pick another.");
            }
        }

        // Step (a): Create patient or match existing one by phone.
        String phoneKey = LandingPageContacts.key(e164);
        List<UUID> matchingPatients = phoneKey == null ? List.of() : jdbc.queryForList("""
                SELECT id FROM patients
                WHERE practice_id = ? AND deleted_at IS NULL AND phone_digits(phone) = ?
                ORDER BY created_at DESC
                """, UUID.class, practiceId, phoneKey);
        boolean existingPatient = !matchingPatients.isEmpty();
        UUID patientId;
        if (existingPatient) {
            patientId = matchingPatients.get(0);
        } else {
            String[] split = splitFullName(cleanName);
            patientId = patientRegistrar.register(new PatientRegistrar.Registration(
                    split[0], split[1], null, null, e164, null, null, null, null, null, null, null, null, lang, OffsetDateTime.now()));
            patientRegistrar.recordAcquisition(patientId, "BOOKING_PAGE");
            consent.record(patientId, MessageChannel.WHATSAPP, true, "PUBLIC_FORM");
        }
        landingPageContacts.record(practiceId, e164, LandingPageContacts.Source.BOOKING);

        // Step (b): Create the appointment atomically for the chosen slot.
        AppointmentRequest appointmentReq = new AppointmentRequest();
        appointmentReq.setPatientId(patientId);
        appointmentReq.setDateTime(request.startsAt());
        appointmentReq.setDurationMinutes(type.getDefaultDurationMinutes());
        appointmentReq.setAppointmentTypeId(type.getId());
        appointmentReq.setPractitionerId(practitionerId);
        appointmentReq.setNotes(cleanNotes);
        AppointmentResponse appointment = appointmentService.createAppointment(appointmentReq, practiceId);

        return new BookedCore(practiceId, patientId, existingPatient, appointment, doctorName, zone);
    }

    private List<SlotFinder.Hold> holdsForDay(UUID practiceId, LocalDate day, ZoneId zone) {
        OffsetDateTime from = day.atStartOfDay(zone).toOffsetDateTime();
        return jdbc.query("""
                SELECT practitioner_id, starts_at, duration_minutes FROM booking_requests
                WHERE practice_id = ? AND status = 'PENDING' AND starts_at >= ? AND starts_at < ?
                """, (rs, i) -> {
            OffsetDateTime start = rs.getObject("starts_at", OffsetDateTime.class);
            return new SlotFinder.Hold(rs.getObject("practitioner_id", UUID.class), start, start.plusMinutes(rs.getInt("duration_minutes")));
        }, practiceId, from, from.plusDays(1));
    }

    private static boolean isHoneypotTripped(String website) {
        return website != null && !website.isBlank();
    }

    private static DirectBookResponse honeypotResponse(DirectBookRequest request) {
        UUID fakeId = UUID.randomUUID();
        return new DirectBookResponse(reference(fakeId), fakeId, request.startsAt(), 30,
                null, null, null, null, null, false, false, false);
    }

    static String sanitizeText(String raw, int maxLen) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim().replaceAll("[ \\t]+", " ");
        if (cleaned.isEmpty()) {
            return null;
        }
        return cleaned.length() > maxLen ? cleaned.substring(0, maxLen) : cleaned;
    }

    static String[] splitFullName(String fullName) {
        String[] parts = fullName.trim().split("\\s+", 2);
        if (parts.length == 1) {
            return new String[]{parts[0], parts[0]};
        }
        return new String[]{parts[0], parts[1]};
    }

    private static String reference(UUID id) {
        return id.toString().substring(0, 8).toUpperCase();
    }
}
