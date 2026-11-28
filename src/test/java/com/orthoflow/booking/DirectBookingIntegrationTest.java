package com.orthoflow.booking;

import com.orthoflow.booking.application.dto.BookingDtos.Availability;
import com.orthoflow.booking.application.dto.BookingDtos.DirectBookRequest;
import com.orthoflow.booking.application.dto.BookingDtos.DirectBookResponse;
import com.orthoflow.booking.application.dto.BookingDtos.Settings;
import com.orthoflow.booking.application.service.BookingService;
import com.orthoflow.booking.application.service.DirectBookingService;
import com.orthoflow.booking.application.service.RegistrationLinkRetryService;
import com.orthoflow.booking.infrastructure.BookingProperties;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.messaging.application.service.PhoneNumbers;
import com.orthoflow.registration.application.service.RegistrationService;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * Verifies atomic booking creation, double-booking prevention, E.164 normalization,
 * registration link privacy (new vs. existing vs. completed), WhatsApp outbox queueing,
 * and resilience when registration link issuance temporarily fails.
 */
class DirectBookingIntegrationTest extends SpringDbTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    @Autowired
    private BookingService booking;
    @Autowired
    private DirectBookingService directBooking;
    @Autowired
    private RegistrationLinkRetryService retryService;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private Tenancy tenancy;

    @MockitoSpyBean
    private RegistrationService registrationService;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID doctorPractitioner;
    private UUID typeId;
    private List<OffsetDateTime> slots;
    private String savedDoctorEmail;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        savedDoctorEmail = properties.getDoctorEmail();
        String testEmail = "direct-" + practice + "@bentosolutions.com";
        properties.setDoctorEmail(testEmail);

        signInTo(practice);
        booking.saveSettings(practice, new Settings(true, 0, 30, 30, true));

        UUID userId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id, active)
                VALUES (?, ?, 'x', 'Achraf', 'Ouajid', 'DOCTOR', ?, TRUE)
                """, userId, testEmail, practice);

        doctorPractitioner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO practitioners (id, practice_id, user_id, display_name, active)
                VALUES (?, ?, ?, 'Dr Achraf Ouajid', TRUE)
                """, doctorPractitioner, practice, userId);

        typeId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar, default_duration_minutes, bookable_online, active)
                VALUES (?, ?, 'INIT', 'Consultation initiale', 'Initial consultation', 'استشارة أولى', 30, TRUE, TRUE)
                """, typeId, practice);

        for (int weekday = 1; weekday <= 7; weekday++) {
            jdbc.update("""
                    INSERT INTO practice_opening_hours (id, practice_id, weekday, closed, open_time, close_time)
                    VALUES (?, ?, ?, FALSE, '09:00', '17:00')
                    """, UUID.randomUUID(), practice, weekday);
        }

        LocalDate day = LocalDate.now(CASABLANCA).plusDays(2);
        if (day.getDayOfWeek() == DayOfWeek.SUNDAY) {
            day = day.plusDays(1);
        }
        LocalDate chosenDay = day;
        signOut();

        Availability free = tenancy.callAs(practice, () -> booking.directAvailability(typeId, chosenDay, 1, null));
        slots = free.days().get(chosenDay).stream()
                .map(t -> chosenDay.atTime(LocalTime.parse(t)).atZone(CASABLANCA).toOffsetDateTime())
                .toList();
    }

    @AfterEach
    void restore() {
        properties.setDoctorEmail(savedDoctorEmail);
        reset(registrationService);
    }

    @Test
    void normalizesPhoneToE164AndRejectsInvalidNumbers() {
        assertThat(PhoneNumbers.normalizeE164("06 61 23 45 67", "+212", "212")).isEqualTo("+212661234567");
        assertThat(PhoneNumbers.normalizeE164("+33 6 12 34 56 78", "+212", "212")).isEqualTo("+33612345678");
        assertThat(PhoneNumbers.normalizeE164("00212661234567", "+212", "212")).isEqualTo("+212661234567");
        assertThat(PhoneNumbers.normalizeE164("12", "+212", "212")).isNull();

        assertThatThrownBy(() -> tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(0), "Nadia Amrani", "abc", "+212", null, "fr", true, null, null),
                null, "127.0.0.1"))).isInstanceOf(ValidationException.class);
    }

    @Test
    void newPatientBookingCreatesPatientAppointmentPersonalLinkAndWhatsappOutbox() {
        DirectBookResponse res = tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(0), "Salma Benali", "06 12 34 56 78", "+212", "Douleur molaire", "fr", true, null, null),
                null, "127.0.0.1"));

        assertThat(res.existingPatient()).isFalse();
        assertThat(res.registrationAlreadyCompleted()).isFalse();
        assertThat(res.registrationUrl()).contains("/public/register/");
        assertThat(res.clinicAddress()).contains("12 rue des Tests");
        assertThat(res.doctorName()).isEqualTo("Dr Achraf Ouajid");

        String storedPhone = jdbc.queryForObject("SELECT phone FROM patients WHERE practice_id = ?", String.class, practice);
        assertThat(storedPhone).isEqualTo("+212612345678");

        Integer appts = jdbc.queryForObject(
                "SELECT count(*) FROM appointments WHERE id = ? AND practitioner_id = ? AND status = 'SCHEDULED'",
                Integer.class, res.appointmentId(), doctorPractitioner);
        assertThat(appts).isOne();

        Map<String, Object> outbox = jdbc.queryForMap(
                "SELECT recipient, purpose, body, status FROM message_outbox WHERE practice_id = ? AND related_id = ?",
                practice, res.appointmentId());
        assertThat(outbox.get("recipient")).isEqualTo("+212612345678");
        assertThat(outbox.get("purpose")).isEqualTo("BOOKING_CONFIRMED");
        assertThat(outbox.get("status")).isEqualTo("QUEUED");
        assertThat((String) outbox.get("body"))
                .contains("Salma Benali")
                .contains("12 rue des Tests")
                .contains(res.registrationUrl());
    }

    @Test
    void preventsDoubleBookingOnSameSlot() {
        tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(0), "Patient One", "0611111111", "+212", null, "fr", true, null, null),
                null, "127.0.0.1"));

        assertThatThrownBy(() -> tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(0), "Patient Two", "0622222222", "+212", null, "fr", true, null, null),
                null, "127.0.0.1"))).isInstanceOf(ConflictException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE practice_id = ?", Integer.class, practice)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients WHERE practice_id = ?", Integer.class, practice)).isOne();
    }

    @Test
    void existingPatientNeverGetsRegistrationLinkInBrowserResponseButReceivesItViaWhatsapp() {
        UUID existingId = PostgresTestSupport.patient(jdbc, practice, "Youssef", "Tazi", "1990-01-01", "+212633445566", null);

        DirectBookResponse res = tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(1), "Youssef Tazi", "0633445566", "+212", null, "fr", true, null, null),
                null, "127.0.0.1"));

        assertThat(res.existingPatient()).isTrue();
        assertThat(res.registrationAlreadyCompleted()).isFalse();
        assertThat(res.registrationUrl()).isNull();
        assertThat(res.registrationLinkSentByWhatsapp()).isTrue();

        UUID linkedPatient = jdbc.queryForObject("SELECT patient_id FROM appointments WHERE id = ?", UUID.class, res.appointmentId());
        assertThat(linkedPatient).isEqualTo(existingId);

        String body = jdbc.queryForObject(
                "SELECT body FROM message_outbox WHERE practice_id = ? AND related_id = ?",
                String.class, practice, res.appointmentId());
        assertThat(body).contains("/public/register/");
    }

    @Test
    void completedRegistrationHidesCtaAndOmitsLinkFromWhatsappConfirmation() {
        UUID completedId = PostgresTestSupport.patient(jdbc, practice, "Imane", "Chraibi", "1992-02-02", "+212644556677", null);
        jdbc.update("UPDATE patients SET registration_completed_at = NOW() WHERE id = ?", completedId);

        DirectBookResponse res = tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(2), "Imane Chraibi", "0644556677", "+212", null, "fr", true, null, null),
                null, "127.0.0.1"));

        assertThat(res.registrationAlreadyCompleted()).isTrue();
        assertThat(res.registrationUrl()).isNull();
        assertThat(res.registrationLinkSentByWhatsapp()).isFalse();

        String body = jdbc.queryForObject(
                "SELECT body FROM message_outbox WHERE practice_id = ? AND related_id = ?",
                String.class, practice, res.appointmentId());
        assertThat(body).doesNotContain("/public/register/");
    }

    @Test
    void registrationLinkFailureStillConfirmsBookingAndQueuesBackgroundRetry() {
        doThrow(new IllegalStateException("Simulated link failure"))
                .when(registrationService).issuePersonalRegistrationUrl(eq(practice), org.mockito.ArgumentMatchers.any());

        DirectBookResponse res = tenancy.callAs(practice, () -> directBooking.bookForConfiguredDoctor(
                new DirectBookRequest(typeId, null, slots.get(3), "Karim Alaoui", "0655667788", "+212", null, "fr", true, null, null),
                null, "127.0.0.1"));

        assertThat(res.appointmentId()).isNotNull();
        assertThat(res.registrationUrl()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE id = ?", Integer.class, res.appointmentId())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registration_link_retries WHERE appointment_id = ? AND completed_at IS NULL",
                Integer.class, res.appointmentId())).isOne();

        // Recover and run background retry job: link is now generated and queued for WhatsApp.
        reset(registrationService);
        int retried = retryService.processPendingRetries();
        assertThat(retried).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM registration_link_retries WHERE appointment_id = ? AND completed_at IS NOT NULL",
                Integer.class, res.appointmentId())).isOne();
        List<String> purposes = jdbc.queryForList(
                "SELECT purpose FROM message_outbox WHERE related_id = ? ORDER BY created_at",
                String.class, res.appointmentId());
        assertThat(purposes).containsExactly("BOOKING_CONFIRMED", "REGISTRATION_INVITE");
    }
}
