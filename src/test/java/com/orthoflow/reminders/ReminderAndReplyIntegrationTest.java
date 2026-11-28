package com.orthoflow.reminders;

import com.orthoflow.booking.application.dto.BookingDtos.Availability;
import com.orthoflow.booking.application.dto.BookingDtos.DirectBookRequest;
import com.orthoflow.booking.application.dto.BookingDtos.DirectBookResponse;
import com.orthoflow.booking.application.dto.BookingDtos.Settings;
import com.orthoflow.booking.application.service.BookingService;
import com.orthoflow.booking.application.service.DirectBookingService;
import com.orthoflow.booking.infrastructure.BookingProperties;
import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.messaging.application.service.WhatsAppWebhookService;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import com.orthoflow.messaging.infrastructure.MessagingProperties.InboundFrom;
import com.orthoflow.reminders.application.service.MessagingSettingsService;
import com.orthoflow.reminders.application.service.ReminderScheduler;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReminderAndReplyIntegrationTest extends SpringDbTest {

    private static final String SESSION = "bento-crm-main";
    private static final ZoneId CLINIC_ZONE = ZoneId.of("Africa/Casablanca");

    @Autowired
    private DirectBookingService directBooking;
    @Autowired
    private BookingService booking;
    @Autowired
    private BookingProperties bookingProperties;
    @Autowired
    private MessagingProperties messagingProperties;
    @Autowired
    private ReminderScheduler scheduler;
    @Autowired
    private MessagingSettingsService settingsService;
    @Autowired
    private WhatsAppWebhookService webhook;
    @Autowired
    private Tenancy tenancy;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID doctorUser;
    private UUID typeId;
    private String savedDoctorEmail;
    private final MessagingProperties.WhatsApp savedWa = new MessagingProperties.WhatsApp();

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        savedDoctorEmail = bookingProperties.getDoctorEmail();
        String doctorEmail = "rem-" + practice + "@bentosolutions.com";
        bookingProperties.setDoctorEmail(doctorEmail);

        MessagingProperties.WhatsApp w = messagingProperties.getWhatsapp();
        savedWa.setSessionId(w.getSessionId());
        savedWa.setPracticeId(w.getPracticeId());
        savedWa.setInboundFrom(w.getInboundFrom());
        w.setSessionId(SESSION);
        w.setPracticeId(practice);
        w.setInboundFrom(InboundFrom.LANDING_PAGE);

        signInTo(practice);
        booking.saveSettings(practice, new Settings(true, 0, 30, 30, true));
        settingsService.put(practice, new MessagingSettingsService.Settings(true, 20, false, 2, false, 3, 1));

        doctorUser = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id, active) "
                        + "VALUES (?, ?, 'x', 'Achraf', 'Ouajid', 'DOCTOR', ?, true)",
                doctorUser, doctorEmail, practice);
        jdbc.update("INSERT INTO practitioners (id, practice_id, user_id, display_name, active) VALUES (?, ?, ?, 'Dr Achraf', true)",
                UUID.randomUUID(), practice, doctorUser);
        typeId = UUID.randomUUID();
        jdbc.update("INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar, default_duration_minutes, bookable_online, active) "
                        + "VALUES (?, ?, 'CONSULT', 'Consultation', 'Consultation', 'استشارة', 30, true, true)",
                typeId, practice);
        for (int weekday = 1; weekday <= 7; weekday++) {
            jdbc.update("""
                    INSERT INTO practice_opening_hours (id, practice_id, weekday, closed, open_time, close_time)
                    VALUES (?, ?, ?, FALSE, '09:00', '17:00')
                    """, UUID.randomUUID(), practice, weekday);
        }
        signOut();
    }

    @AfterEach
    void restore() {
        bookingProperties.setDoctorEmail(savedDoctorEmail);
        MessagingProperties.WhatsApp w = messagingProperties.getWhatsapp();
        w.setSessionId(savedWa.getSessionId());
        w.setPracticeId(savedWa.getPracticeId());
        w.setInboundFrom(savedWa.getInboundFrom());
    }

    private void deliverInbound(String phone, String body) {
        String json = """
                {"events":[{"id":"%s","sessionId":"%s","type":"message.upsert",
                  "data":{"direction":"IN","wamid":"%s","phoneE164":"%s","body":"%s"}}]}
                """.formatted(UUID.randomUUID(), SESSION, UUID.randomUUID(), phone, body);
        assertThat(tenancy.callAcrossClinics(() -> webhook.handle(json))).isEmpty();
    }

    @Test
    void twentyHourReminderIsIdempotentOmitCompletedRegistrationAndRecords123Replies() {
        LocalDate visitDay = LocalDate.now(CLINIC_ZONE).plusDays(2);
        if (visitDay.getDayOfWeek() == DayOfWeek.SUNDAY) {
            visitDay = visitDay.plusDays(1);
        }
        LocalDate chosenDay = visitDay;
        Availability free = tenancy.callAs(practice, () -> booking.directAvailability(typeId, chosenDay, 1, null));
        List<OffsetDateTime> slots = free.days().get(chosenDay).stream()
                .map(t -> chosenDay.atTime(LocalTime.parse(t)).atZone(CLINIC_ZONE).toOffsetDateTime())
                .toList();
        assertThat(slots).hasSizeGreaterThanOrEqualTo(3);

        DirectBookResponse p1 = tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(0), "Sara OnTime", "0611111111", "+212", null, "fr", true, null, null),
                null, "10.0.0.1"));
        DirectBookResponse p2 = tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(1), "Omar Late", "0622222222", "+212", null, "fr", true, null, null),
                null, "10.0.0.2"));
        DirectBookResponse p3 = tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(2), "Lina Cancel", "0633333333", "+212", null, "fr", true, null, null),
                null, "10.0.0.3"));

        UUID p2PatientId = jdbc.queryForObject("SELECT patient_id FROM appointments WHERE id = ?", UUID.class, p2.appointmentId());
        jdbc.update("UPDATE patients SET registration_completed_at = now() WHERE id = ?", p2PatientId);

        OffsetDateTime at20hClinic = chosenDay.minusDays(1).atTime(20, 0).atZone(CLINIC_ZONE).toOffsetDateTime();
        // Run twice to simulate scheduler restart / duplicate trigger at 20:00: idempotent!
        invokeSchedulerFor(at20hClinic);
        invokeSchedulerFor(at20hClinic);

        Integer reminderCount = jdbc.queryForObject(
                "SELECT count(*) FROM message_outbox WHERE practice_id = ? AND purpose = 'APPOINTMENT_REMINDER'",
                Integer.class, practice);
        assertThat(reminderCount).isEqualTo(3);

        String bodyIncomplete = jdbc.queryForObject(
                "SELECT body FROM message_outbox WHERE related_id = ? AND purpose = 'APPOINTMENT_REMINDER'",
                String.class, p1.appointmentId());
        String bodyCompleted = jdbc.queryForObject(
                "SELECT body FROM message_outbox WHERE related_id = ? AND purpose = 'APPOINTMENT_REMINDER'",
                String.class, p2.appointmentId());
        assertThat(bodyIncomplete).contains("/public/register/");
        assertThat(bodyCompleted).doesNotContain("/public/register/");

        deliverInbound("+212611111111", "1");
        deliverInbound("+212622222222", "2 - je serai un peu en retard");
        deliverInbound("+212633333333", "3");

        var appt1 = jdbc.queryForMap("SELECT status, reply_status, reply_note FROM appointments WHERE id = ?", p1.appointmentId());
        assertThat(appt1.get("status")).isEqualTo("CONFIRMED");
        assertThat(appt1.get("reply_status")).isEqualTo("CONFIRMED_ON_TIME");

        var appt2 = jdbc.queryForMap("SELECT status, reply_status, reply_note FROM appointments WHERE id = ?", p2.appointmentId());
        assertThat(appt2.get("status")).isEqualTo("LATE");
        assertThat(appt2.get("reply_status")).isEqualTo("CONFIRMED_LATE");
        assertThat(appt2.get("reply_note")).isEqualTo("2 - je serai un peu en retard");

        var appt3 = jdbc.queryForMap("SELECT status, reply_status FROM appointments WHERE id = ?", p3.appointmentId());
        assertThat(appt3.get("status")).isEqualTo("CANCELLED");
        assertThat(appt3.get("reply_status")).isEqualTo("CANNOT_COME");

        Integer staffLateNotices = jdbc.queryForObject(
                "SELECT count(*) FROM message_outbox WHERE practice_id = ? AND recipient_user_id = ? AND related_id = ? AND purpose = 'APPOINTMENT_CONFIRMED'",
                Integer.class, practice, doctorUser, p2.appointmentId());
        assertThat(staffLateNotices).isEqualTo(1);

        Integer staffCancelNotices = jdbc.queryForObject(
                "SELECT count(*) FROM message_outbox WHERE practice_id = ? AND recipient_user_id = ? AND related_id = ? AND purpose = 'APPOINTMENT_CANCELLED'",
                Integer.class, practice, doctorUser, p3.appointmentId());
        assertThat(staffCancelNotices).isEqualTo(1);
    }

    private void invokeSchedulerFor(OffsetDateTime moment) {
        try {
            var m = ReminderScheduler.class.getDeclaredMethod("runFor", UUID.class, OffsetDateTime.class);
            m.setAccessible(true);
            m.invoke(scheduler, practice, moment);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
