package com.orthoflow.booking;

import com.orthoflow.booking.application.dto.BookingDtos.Availability;
import com.orthoflow.booking.application.dto.BookingDtos.PublicInfo;
import com.orthoflow.booking.application.dto.BookingDtos.Settings;
import com.orthoflow.booking.application.service.BookingService;
import com.orthoflow.booking.infrastructure.BookingProperties;
import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies live slot availability for the configured doctor account
 * ({@code achraf.ouajid@bentosolutions.com}), respecting working hours, breaks,
 * existing appointments, blocked time, and slot duration.
 */
class BookingAvailabilityTest extends SpringDbTest {

    private static final ZoneId CASABLANCA = ZoneId.of("Africa/Casablanca");

    @Autowired
    private BookingService booking;
    @Autowired
    private BookingProperties properties;
    @Autowired
    private Tenancy tenancy;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID doctorPractitioner;
    private UUID otherPractitioner;
    private UUID type45;
    private LocalDate targetDay;
    private String savedDoctorEmail;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        savedDoctorEmail = properties.getDoctorEmail();
        String testEmail = "doctor-" + practice + "@bentosolutions.com";
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

        otherPractitioner = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO practitioners (id, practice_id, display_name, active)
                VALUES (?, ?, 'Dr Other', TRUE)
                """, otherPractitioner, practice);

        type45 = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar, default_duration_minutes, bookable_online, active)
                VALUES (?, ?, 'ORTHO45', 'Consultation initiale', 'Initial consult', 'استشارة', 45, TRUE, TRUE)
                """, type45, practice);

        for (int weekday = 1; weekday <= 6; weekday++) {
            jdbc.update("""
                    INSERT INTO practice_opening_hours (id, practice_id, weekday, closed, open_time, close_time, break_start, break_end)
                    VALUES (?, ?, ?, FALSE, '09:00', '17:00', '12:00', '14:00')
                    """, UUID.randomUUID(), practice, weekday);
        }
        jdbc.update("""
                INSERT INTO practice_opening_hours (id, practice_id, weekday, closed, open_time, close_time)
                VALUES (?, ?, 7, TRUE, '09:00', '17:00')
                """, UUID.randomUUID(), practice);

        LocalDate candidate = LocalDate.now(CASABLANCA).plusDays(2);
        if (candidate.getDayOfWeek() == DayOfWeek.SUNDAY) {
            candidate = candidate.plusDays(1);
        }
        targetDay = candidate;
        signOut();
    }

    @AfterEach
    void restore() {
        properties.setDoctorEmail(savedDoctorEmail);
    }

    @Test
    void respectsWorkingHoursBreaksAppointmentsAbsencesBlocksAndDuration() {
        UUID patientId = PostgresTestSupport.patient(jdbc, practice, "Sara", "Bennani", "1995-05-05", "+212612345678", null);
        OffsetDateTime apptStart = targetDay.atTime(9, 30).atZone(CASABLANCA).toOffsetDateTime();
        jdbc.update("""
                INSERT INTO appointments (id, practice_id, patient_id, practitioner_id, date_time, duration_minutes, type, status)
                VALUES (?, ?, ?, ?, ?, 30, 'Contrôle', 'CONFIRMED')
                """, UUID.randomUUID(), practice, patientId, doctorPractitioner, apptStart);

        // Another practitioner's appointment at 10:30 must NOT block our target doctor.
        OffsetDateTime otherApptStart = targetDay.atTime(10, 30).atZone(CASABLANCA).toOffsetDateTime();
        jdbc.update("""
                INSERT INTO appointments (id, practice_id, patient_id, practitioner_id, date_time, duration_minutes, type, status)
                VALUES (?, ?, ?, ?, ?, 30, 'Contrôle', 'CONFIRMED')
                """, UUID.randomUUID(), practice, patientId, otherPractitioner, otherApptStart);

        // Calendar block for target doctor 15:00-16:00.
        OffsetDateTime blockStart = targetDay.atTime(15, 0).atZone(CASABLANCA).toOffsetDateTime();
        OffsetDateTime blockEnd = targetDay.atTime(16, 0).atZone(CASABLANCA).toOffsetDateTime();
        jdbc.update("""
                INSERT INTO calendar_events (id, practice_id, practitioner_id, title, starts_at, ends_at)
                VALUES (?, ?, ?, 'Réunion', ?, ?)
                """, UUID.randomUUID(), practice, doctorPractitioner, blockStart, blockEnd);

        // Absence for target doctor 16:00-17:00.
        OffsetDateTime awayStart = targetDay.atTime(16, 0).atZone(CASABLANCA).toOffsetDateTime();
        OffsetDateTime awayEnd = targetDay.atTime(17, 0).atZone(CASABLANCA).toOffsetDateTime();
        jdbc.update("""
                INSERT INTO practitioner_absences (id, practice_id, practitioner_id, starts_at, ends_at, reason)
                VALUES (?, ?, ?, ?, ?, 'OTHER')
                """, UUID.randomUUID(), practice, doctorPractitioner, awayStart, awayEnd);

        PublicInfo info = tenancy.callAs(practice, () -> booking.directInfo());
        assertThat(info.lockedPractitionerId()).isEqualTo(doctorPractitioner);
        assertThat(info.lockedPractitionerName()).isEqualTo("Dr Achraf Ouajid");

        Availability availability = tenancy.callAs(practice, () -> booking.directAvailability(type45, targetDay, 1, null));
        List<String> slots = availability.days().get(targetDay);

        // With 45-min duration & 30-min step:
        // 09:00..09:45 overlaps 09:30 appointment -> excluded
        // 09:30..10:15 overlaps 09:30 appointment -> excluded
        // 10:00..10:45 free! (other doctor at 10:30 does not block)
        // 10:30..11:15 free!
        // 11:00..11:45 free!
        // 11:30..12:15 overlaps 12:00-14:00 break -> excluded
        // 12:00..13:30 break -> excluded
        // 14:00..14:45 free!
        // 14:30..15:15 overlaps 15:00-16:00 block -> excluded
        // 15:00..17:00 blocked + absent -> excluded
        assertThat(slots).containsExactly("10:00", "10:30", "11:00", "14:00");
    }
}
