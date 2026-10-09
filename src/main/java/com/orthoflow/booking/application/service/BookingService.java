package com.orthoflow.booking.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.booking.application.dto.BookingDtos.*;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.application.service.ConsentService;
import com.orthoflow.messaging.application.service.LandingPageContacts;
import com.orthoflow.messaging.application.service.MessageService;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientRegistrar;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLink;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.application.service.AppointmentService;
import com.orthoflow.scheduling.application.service.SlotFinder;
import com.orthoflow.scheduling.application.service.SlotFinder.Hold;
import com.orthoflow.scheduling.domain.model.AppointmentType;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentTypeJpaRepository;
import com.orthoflow.settings.application.service.PracticeProfileService;
import com.orthoflow.settings.domain.model.PracticeProfile;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Online booking. A patient picks a kind of visit and a free slot on a public page;
 * what that creates is a REQUEST, not an appointment — nobody is booked into the
 * diary by a stranger on the internet — which staff confirm or decline, unless the
 * clinic has chosen auto-confirm (off by default). Pending requests hold their slot
 * so two people cannot ask for the same time, and the slot is checked again at the
 * moment of the request, not trusted from when the page loaded.
 */
@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final JdbcTemplate jdbc;
    private final PublicLinkService publicLinks;
    private final PracticeProfileService profiles;
    private final AppointmentTypeJpaRepository types;
    private final PractitionerService practitionerService;
    private final SlotFinder slotFinder;
    private final AppointmentService appointmentService;
    private final PatientLookup patientLookup;
    private final PatientRegistrar patientRegistrar;
    private final ConsentService consent;
    private final MessageService messages;
    private final StaffNotifier notifier;
    private final PracticeZone practiceZone;
    private final LiveEventPublisher liveEvents;
    private final LandingPageContacts landingPageContacts;

    // ── Settings ──
    @Transactional
    public Settings settings(UUID practiceId) {
        jdbc.update("INSERT INTO booking_settings (practice_id) VALUES (?) ON CONFLICT DO NOTHING", practiceId);
        return jdbc.queryForObject("SELECT enabled, lead_time_hours, max_days_ahead, slot_step_minutes, auto_confirm FROM booking_settings WHERE practice_id = ?",
                (rs, i) -> new Settings(rs.getBoolean(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getBoolean(5)), practiceId);
    }

    @Transactional
    public Settings saveSettings(UUID practiceId, Settings s) {
        settings(practiceId);
        jdbc.update("UPDATE booking_settings SET enabled = ?, lead_time_hours = ?, max_days_ahead = ?, slot_step_minutes = ?, auto_confirm = ?, updated_at = NOW() WHERE practice_id = ?",
                s.enabled(), s.leadTimeHours(), s.maxDaysAhead(), s.slotStepMinutes(), s.autoConfirm(), practiceId);
        return settings(practiceId);
    }

    // ── Public ──
    @Transactional
    public PublicInfo info(String token) {
        PublicLink link = publicLinks.resolve(token, PublicLinkPurpose.BOOKING);
        UUID practiceId = link.getPracticeId();
        Settings s = requireEnabled(practiceId);
        PracticeProfile clinic = profiles.require(practiceId);
        return new PublicInfo(clinic.getName(), clinic.getPhone(), clinic.getCity(),
                types.findByPracticeIdAndActiveTrueAndBookableOnlineTrueOrderByDisplayOrderAscNameFrAsc(practiceId).stream()
                        .map(t -> new PublicType(t.getId(), t.getNameFr(), t.getNameEn(), t.getNameAr(), t.getColor(), t.getDefaultDurationMinutes())).toList(),
                practitionerService.list(practiceId, false).stream().map(p -> new PublicPractitioner(p.id(), p.displayName(), p.color())).toList(),
                s.maxDaysAhead(), clinic.getDefaultLanguage(), practiceZone.of(practiceId).getId());
    }

    @Transactional
    public Availability availability(String token, UUID typeId, UUID practitionerId, LocalDate from, int days) {
        PublicLink link = publicLinks.resolve(token, PublicLinkPurpose.BOOKING);
        UUID practiceId = link.getPracticeId();
        Settings s = requireEnabled(practiceId);
        AppointmentType type = bookableType(practiceId, typeId);
        checkPractitioner(practiceId, practitionerId);
        ZoneId zone = practiceZone.of(practiceId);
        LocalDate today = LocalDate.now(zone);
        LocalDate start = from == null || from.isBefore(today) ? today : from;
        LocalDate last = today.plusDays(s.maxDaysAhead());
        int span = Math.min(Math.max(days, 1), 31);
        Map<LocalDate, List<String>> out = new LinkedHashMap<>();
        for (int i = 0; i < span && !start.plusDays(i).isAfter(last); i++) {
            LocalDate day = start.plusDays(i);
            List<String> times = slotFinder.freeSlots(practiceId, day, type.getDefaultDurationMinutes(), practitionerId,
                    s.leadTimeHours(), s.slotStepMinutes(), holds(practiceId, day, zone)).stream()
                    .map(t -> TIME.format(t.atZoneSameInstant(zone))).toList();
            if (!times.isEmpty()) {
                out.put(day, times);
            }
        }
        return new Availability(out);
    }

    /**
     * Takes a request from the public page. A bot that fills the hidden field gets
     * the same success as anyone and nothing is stored. A slot that has gone since
     * the page loaded is refused plainly.
     */
    @Transactional
    public Received submit(String token, Submit r) {
        PublicLink link = publicLinks.resolve(token, PublicLinkPurpose.BOOKING);
        UUID practiceId = link.getPracticeId();
        if (r.website() != null && !r.website().isBlank()) {
            return new Received(reference(UUID.randomUUID()));
        }
        Settings s = requireEnabled(practiceId);
        AppointmentType type = bookableType(practiceId, r.appointmentTypeId());
        checkPractitioner(practiceId, r.practitionerId());
        if (blank(r.phone()) && blank(r.email())) {
            throw new ValidationException("Give a phone number or an email so the clinic can answer you");
        }
        ZoneId zone = practiceZone.of(practiceId);
        OffsetDateTime now = OffsetDateTime.now();
        if (r.startsAt().isBefore(now.plusHours(s.leadTimeHours())) || r.startsAt().isAfter(now.plusDays(s.maxDaysAhead()))) {
            throw new ValidationException("That time cannot be booked online");
        }
        LocalDate day = r.startsAt().atZoneSameInstant(zone).toLocalDate();
        if (!slotFinder.isFree(practiceId, r.startsAt(), type.getDefaultDurationMinutes(), r.practitionerId(), holds(practiceId, day, zone))) {
            throw new ConflictException("That time is no longer available. Please pick another.");
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO booking_requests (id, practice_id, appointment_type_id, practitioner_id, starts_at, duration_minutes, first_name, last_name,
                                              phone, email, date_of_birth, note, language, source)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, practiceId, type.getId(), r.practitionerId(), r.startsAt(), type.getDefaultDurationMinutes(), r.firstName().trim(),
                r.lastName().trim(), blankToNull(r.phone()), blankToNull(r.email()), r.dateOfBirth(), blankToNull(r.note()),
                r.language() == null ? "fr" : r.language(), r.fromLandingPage() ? "LANDING_PAGE" : "BOOKING_PAGE");
        if (r.fromLandingPage()) {
            // Their WhatsApp replies are this clinic's to read, even on a number shared with the CRM.
            landingPageContacts.record(practiceId, r.phone(), LandingPageContacts.Source.BOOKING);
        }
        acknowledge(practiceId, id, r, zone);
        if (s.autoConfirm()) {
            try {
                confirm(practiceId, null, id, new Confirm(null, null, null, null, false));
                return new Received(reference(id));
            } catch (RuntimeException e) {
                // Could not auto-confirm (no free practitioner, a conflict): it stays a request for staff.
                log.warn("Auto-confirm of booking request {} failed: {}", id, e.getMessage());
            }
        }
        notifier.toPermission(practiceId, Permission.BOOKING_REVIEW, MessagePurpose.BOOKING_RECEIVED, "Nouvelle demande de rendez-vous",
                r.firstName().trim() + " " + r.lastName().trim() + " — " + DAY.format(r.startsAt().atZoneSameInstant(zone)) + " à "
                        + TIME.format(r.startsAt().atZoneSameInstant(zone)) + " (" + type.getNameFr() + ")", "BOOKING_REQUEST", id);
        liveEvents.publish(practiceId, "booking-request", id);
        return new Received(reference(id));
    }

    // ── Staff ──
    @Transactional(readOnly = true)
    public List<RequestView> list(UUID practiceId, String status) {
        String filter = status == null || status.isBlank() ? "PENDING" : status;
        ZoneId zone = practiceZone.of(practiceId);
        List<RequestView> rows = jdbc.query("""
                SELECT r.*, t.name_fr AS type_name FROM booking_requests r JOIN appointment_types t ON t.id = r.appointment_type_id
                WHERE r.practice_id = ? AND r.status = ? ORDER BY r.starts_at LIMIT 200
                """, (rs, i) -> view(rs), practiceId, filter);
        Map<UUID, Practitioner> practitioners = practitionerService.byIds(rows.stream().map(RequestView::practitionerId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return rows.stream().map(v -> new RequestView(v.id(), v.appointmentTypeId(), v.typeName(), v.practitionerId(),
                v.practitionerId() == null || practitioners.get(v.practitionerId()) == null ? null : practitioners.get(v.practitionerId()).getDisplayName(),
                v.startsAt(), v.durationMinutes(), v.firstName(), v.lastName(), v.phone(), v.email(), v.dateOfBirth(), v.note(), v.language(),
                v.status(), v.patientId(), v.appointmentId(), v.declineReason(), v.createdAt(),
                "PENDING".equals(v.status()) && slotFinder.isFree(practiceId, v.startsAt(), v.durationMinutes(), v.practitionerId(),
                        holdsExcluding(practiceId, v.startsAt().atZoneSameInstant(zone).toLocalDate(), zone, v.id())), v.source())).toList();
    }

    @Transactional(readOnly = true)
    public long pendingCount(UUID practiceId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM booking_requests WHERE practice_id = ? AND status = 'PENDING'", Long.class, practiceId);
        return n == null ? 0 : n;
    }

    /**
     * Books the request into the diary. The patient is the one staff chose, or a new
     * one made from the form (with the consent given on it recorded). The booking goes
     * through the normal path, so double-booking, absences and events are enforced as
     * for any appointment. {@code actorId} is null for an auto-confirm.
     */
    @Transactional
    public AppointmentResponse confirm(UUID practiceId, UUID actorId, UUID requestId, Confirm c) {
        Map<String, Object> row = pendingRow(practiceId, requestId);
        UUID typeId = (UUID) row.get("appointment_type_id");
        int duration = (Integer) row.get("duration_minutes");
        OffsetDateTime startsAt = c.startsAt() != null ? c.startsAt() : row.get("starts_at") instanceof java.sql.Timestamp t ? t.toInstant().atOffset(ZoneOffset.UTC) : (OffsetDateTime) row.get("starts_at");
        UUID practitionerId = c.practitionerId() != null ? c.practitionerId() : (UUID) row.get("practitioner_id");
        if (practitionerId == null) {
            practitionerId = firstFreePractitioner(practiceId, startsAt, duration, requestId);
        } else {
            practitionerService.require(practiceId, practitionerId);
        }
        String phone = (String) row.get("phone");
        String email = (String) row.get("email");
        UUID patientId = c.patientId();
        if (patientId != null) {
            if (!patientLookup.exists(patientId)) {
                throw new NotFoundException("Patient not found");
            }
        } else {
            patientId = patientRegistrar.register(new PatientRegistrar.Registration((String) row.get("first_name"), (String) row.get("last_name"),
                    null, row.get("date_of_birth") == null ? null : ((java.sql.Date) row.get("date_of_birth")).toLocalDate(), phone, email, null, null, null,
                    null, null, null, null, (String) row.get("language"), OffsetDateTime.now()));
        }
        patientRegistrar.recordAcquisition(patientId, (String) row.get("source"));
        // The person agreed to be contacted on the form they filled in.
        if (!blank(phone)) consent.record(patientId, MessageChannel.WHATSAPP, true, "PUBLIC_FORM");
        if (!blank(email)) consent.record(patientId, MessageChannel.EMAIL, true, "PUBLIC_FORM");

        AppointmentRequest booking = new AppointmentRequest();
        booking.setPatientId(patientId);
        booking.setDateTime(startsAt);
        booking.setDurationMinutes(duration);
        booking.setAppointmentTypeId(typeId);
        booking.setPractitionerId(practitionerId);
        booking.setChairId(c.chairId());
        booking.setNotes((String) row.get("note"));
        booking.setIgnoreBlocks(c.ignoreBlocks());
        AppointmentResponse appointment = appointmentService.createAppointment(booking, practiceId);

        jdbc.update("UPDATE booking_requests SET status = 'CONFIRMED', patient_id = ?, appointment_id = ?, practitioner_id = ?, starts_at = ?, decided_by = ?, decided_at = NOW() WHERE id = ?",
                patientId, appointment.getId(), practitionerId, startsAt, actorId, requestId);
        ZoneId zone = practiceZone.of(practiceId);
        messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(blank(phone) ? MessageChannel.EMAIL : MessageChannel.WHATSAPP)
                .purpose(MessagePurpose.BOOKING_CONFIRMED).patientId(patientId).language((String) row.get("language"))
                .variables(Map.of("date", DAY.format(startsAt.atZoneSameInstant(zone)), "time", TIME.format(startsAt.atZoneSameInstant(zone))))
                .relatedType("APPOINTMENT").relatedId(appointment.getId()).skipConsentCheck(true).build());
        liveEvents.publish(practiceId, "booking-request", requestId);
        return appointment;
    }

    @Transactional
    public void decline(UUID practiceId, UUID actorId, UUID requestId, String reason) {
        Map<String, Object> row = pendingRow(practiceId, requestId);
        jdbc.update("UPDATE booking_requests SET status = 'DECLINED', decline_reason = ?, decided_by = ?, decided_at = NOW() WHERE id = ?", blankToNull(reason), actorId, requestId);
        String phone = (String) row.get("phone");
        UUID patientId = (UUID) row.get("patient_id");
        messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(blank(phone) ? MessageChannel.EMAIL : MessageChannel.WHATSAPP)
                .purpose(MessagePurpose.BOOKING_DECLINED).patientId(patientId).recipient(blank(phone) ? (String) row.get("email") : phone)
                .language((String) row.get("language")).variables(Map.of("patientName", row.get("first_name") + " " + row.get("last_name")))
                .skipConsentCheck(true).build());
        liveEvents.publish(practiceId, "booking-request", requestId);
    }

    /** A request for a time that has passed will never be answered; mark it so it leaves the inbox. */
    @Scheduled(cron = "${orthoflow.booking.expire-cron:0 10 * * * *}")
    @Transactional
    public void expireStale() {
        int n = jdbc.update("UPDATE booking_requests SET status = 'EXPIRED' WHERE status = 'PENDING' AND starts_at < NOW()");
        if (n > 0) {
            log.info("Expired {} stale booking request(s)", n);
        }
    }

    // ── helpers ──
    private Settings requireEnabled(UUID practiceId) {
        Settings s = settings(practiceId);
        if (!s.enabled()) {
            // A clinic that has not switched online booking on has no booking page: the same "not found" as a bad link.
            throw new NotFoundException("This link is not valid");
        }
        return s;
    }

    private AppointmentType bookableType(UUID practiceId, UUID typeId) {
        return types.findByIdAndPracticeId(typeId, practiceId).filter(t -> t.isActive() && t.isBookableOnline())
                .orElseThrow(() -> new ValidationException("That kind of visit cannot be booked online"));
    }

    private void checkPractitioner(UUID practiceId, UUID practitionerId) {
        if (practitionerId != null && practitionerService.list(practiceId, false).stream().noneMatch(p -> p.id().equals(practitionerId))) {
            throw new ValidationException("That practitioner is not available for online booking");
        }
    }

    private UUID firstFreePractitioner(UUID practiceId, OffsetDateTime startsAt, int duration, UUID excludingRequest) {
        ZoneId zone = practiceZone.of(practiceId);
        List<Hold> holds = holdsExcluding(practiceId, startsAt.atZoneSameInstant(zone).toLocalDate(), zone, excludingRequest);
        for (var p : practitionerService.list(practiceId, false)) {
            if (slotFinder.isFree(practiceId, startsAt, duration, p.id(), holds)) {
                return p.id();
            }
        }
        // No practitioners at all, or none free: a clinic without practitioners books by chair, so null is fine there.
        if (practitionerService.list(practiceId, false).isEmpty()) {
            return null;
        }
        throw new ConflictException("No practitioner is free at that time");
    }

    private List<Hold> holds(UUID practiceId, LocalDate day, ZoneId zone) {
        return holdsExcluding(practiceId, day, zone, null);
    }

    private List<Hold> holdsExcluding(UUID practiceId, LocalDate day, ZoneId zone, UUID excluding) {
        OffsetDateTime from = day.atStartOfDay(zone).toOffsetDateTime();
        return jdbc.query("SELECT practitioner_id, starts_at, duration_minutes FROM booking_requests WHERE practice_id = ? AND status = 'PENDING' AND starts_at >= ? AND starts_at < ? AND id <> ?",
                (rs, i) -> {
                    OffsetDateTime start = rs.getObject("starts_at", OffsetDateTime.class);
                    return new Hold(rs.getObject("practitioner_id", UUID.class), start, start.plusMinutes(rs.getInt("duration_minutes")));
                }, practiceId, from, from.plusDays(1), excluding == null ? UUID.fromString("00000000-0000-0000-0000-000000000000") : excluding);
    }

    private Map<String, Object> pendingRow(UUID practiceId, UUID requestId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM booking_requests WHERE id = ? AND practice_id = ?", requestId, practiceId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Booking request not found");
        }
        if (!"PENDING".equals(rows.get(0).get("status"))) {
            throw new ConflictException("This request was already " + String.valueOf(rows.get(0).get("status")).toLowerCase());
        }
        return rows.get(0);
    }

    private void acknowledge(UUID practiceId, UUID requestId, Submit r, ZoneId zone) {
        boolean byPhone = !blank(r.phone());
        messages.enqueue(OutgoingMessage.builder().practiceId(practiceId).channel(byPhone ? MessageChannel.WHATSAPP : MessageChannel.EMAIL)
                .purpose(MessagePurpose.BOOKING_RECEIVED).recipient(byPhone ? r.phone() : r.email()).language(r.language() == null ? "fr" : r.language())
                .variables(Map.of("patientName", r.firstName().trim() + " " + r.lastName().trim())).relatedType("BOOKING_REQUEST")
                .relatedId(requestId).dedupeKey("booking-ack:" + requestId).skipConsentCheck(true).build());
    }

    private RequestView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RequestView(rs.getObject("id", UUID.class), rs.getObject("appointment_type_id", UUID.class), rs.getString("type_name"),
                rs.getObject("practitioner_id", UUID.class), null, rs.getObject("starts_at", OffsetDateTime.class), rs.getInt("duration_minutes"),
                rs.getString("first_name"), rs.getString("last_name"), rs.getString("phone"), rs.getString("email"),
                rs.getObject("date_of_birth", LocalDate.class), rs.getString("note"), rs.getString("language"), rs.getString("status"),
                rs.getObject("patient_id", UUID.class), rs.getObject("appointment_id", UUID.class), rs.getString("decline_reason"),
                rs.getObject("created_at", OffsetDateTime.class), false, rs.getString("source"));
    }

    private static String reference(UUID id) {
        return id.toString().substring(0, 8).toUpperCase();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankToNull(String s) {
        return blank(s) ? null : s.trim();
    }
}
