package com.orthoflow.booking;

import com.orthoflow.booking.application.dto.BookingDtos.Availability;
import com.orthoflow.booking.application.dto.BookingDtos.Settings;
import com.orthoflow.booking.application.dto.BookingDtos.Submit;
import com.orthoflow.booking.application.service.BookingService;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The public booking page is open to anyone, so a script must not be able to use it to flood the
 * clinic's inbox, hold every free slot, or push the clinic's WhatsApp number at someone else's phone.
 */
class BookingAbuseLimitsTest extends SpringDbTest {

    @Autowired
    private BookingService booking;
    @Autowired
    private PublicLinkService links;
    @Autowired
    private Tenancy tenancy;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID type;
    private String token;
    private List<OffsetDateTime> slots;

    @BeforeEach
    void onlineBookingOn() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        signInTo(practice);
        booking.saveSettings(practice, new Settings(true, 0, 30, 30, false));
        token = links.sharedToken(practice, PublicLinkPurpose.BOOKING);
        type = UUID.randomUUID();
        jdbc.update("INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar, bookable_online) VALUES (?, ?, 'CONS', 'Consultation', 'Consultation', 'استشارة', true)",
                type, practice);
        jdbc.update("INSERT INTO practitioners (id, practice_id, display_name) VALUES (?, ?, 'Dr Tazi')", UUID.randomUUID(), practice);
        for (int weekday = 1; weekday <= 7; weekday++) {
            jdbc.update("INSERT INTO practice_opening_hours (id, practice_id, weekday, open_time, close_time) VALUES (?, ?, ?, '09:00', '18:00')",
                    UUID.randomUUID(), practice, weekday);
        }
        signOut();
        ZoneId casablanca = ZoneId.of("Africa/Casablanca");
        slots = new ArrayList<>();
        tenancy.callAs(practice, () -> {
            Availability free = booking.availability(token, type, null, LocalDate.now(casablanca).plusDays(1), 14);
            free.days().forEach((day, times) -> times.forEach(t -> slots.add(day.atTime(LocalTime.parse(t)).atZone(casablanca).toOffsetDateTime())));
            return null;
        });
    }

    private void request(int slot, String phone, String email) {
        tenancy.callAs(practice, () -> booking.submit(token, new Submit(type, null, slots.get(slot), "Salma", "Idrissi", phone, email,
                null, null, "fr", true, null)));
    }

    private int stored() {
        return jdbc.queryForObject("SELECT count(*) FROM booking_requests WHERE practice_id = ?", Integer.class, practice);
    }

    @Test
    void aPhoneNumberThatCannotBeOneIsRefused() {
        assertThatThrownBy(() -> request(0, "abc", null)).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> request(0, "12", null)).isInstanceOf(ValidationException.class);
        assertThat(stored()).isZero();
    }

    @Test
    void oneNumberCannotHoldMoreThanTwoSlotsAtOnce() {
        request(0, "06 00 00 11 11", null);
        request(1, "+212 600 001 111", null);

        assertThatThrownBy(() -> request(2, "0600001111", null)).isInstanceOf(ConflictException.class);
        assertThat(stored()).isEqualTo(2);
    }

    @Test
    void oneEmailCannotHoldMoreThanTwoSlotsAtOnceWhateverItsCase() {
        request(0, null, "someone@example.com");
        request(1, null, "Someone@Example.com");

        assertThatThrownBy(() -> request(2, null, "SOMEONE@example.com")).isInstanceOf(ConflictException.class);
    }

    @Test
    void aDifferentNumberIsNotAffected() {
        request(0, "0600001111", null);
        request(1, "0600001111", null);

        request(2, "0600002222", null);

        assertThat(stored()).isEqualTo(3);
    }

    @Test
    void declinedRequestsStillCountTowardsTheDay() {
        for (int i = 0; i < 4; i++) {
            request(i, "0600003333", null);
            jdbc.update("UPDATE booking_requests SET status = 'DECLINED' WHERE practice_id = ?", practice);
        }

        assertThatThrownBy(() -> request(5, "0600003333", null)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aClinicWithTooManyRequestsWaitingTakesNoMore() {
        for (int i = 0; i < BookingService.MAX_PENDING_PER_CLINIC; i++) {
            jdbc.update("""
                    INSERT INTO booking_requests (id, practice_id, appointment_type_id, starts_at, duration_minutes, first_name, last_name, phone)
                    VALUES (?, ?, ?, NOW() + INTERVAL '5 days', 30, 'A', 'B', ?)
                    """, UUID.randomUUID(), practice, type, "06" + String.format("%08d", 50_000_000 + i));
        }

        assertThatThrownBy(() -> request(0, "0600009999", null)).isInstanceOf(ConflictException.class);
    }
}
