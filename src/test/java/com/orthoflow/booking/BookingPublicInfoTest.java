package com.orthoflow.booking;

import com.orthoflow.booking.application.dto.BookingDtos.PublicInfo;
import com.orthoflow.booking.application.dto.BookingDtos.Settings;
import com.orthoflow.booking.application.service.BookingService;
import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the public booking page is told. The free times it shows are clock times in the clinic's
 * zone, so it has to be told the zone: a page that guessed (from the visitor's browser, from a
 * fixed +01:00) would send a different moment than the one the patient clicked whenever the
 * clinic's offset changes, which in Morocco it does for Ramadan.
 */
class BookingPublicInfoTest extends SpringDbTest {

    @Autowired
    private BookingService booking;
    @Autowired
    private PublicLinkService links;
    @Autowired
    private Tenancy tenancy;

    @Test
    void thePublicInfoNamesTheClinicsTimeZone() {
        JdbcTemplate jdbc = PostgresTestSupport.jdbc();
        UUID practice = PostgresTestSupport.newPractice(jdbc);
        signInTo(practice);
        booking.saveSettings(practice, new Settings(true, 0, 30, 30, false));
        String token = links.sharedToken(practice, PublicLinkPurpose.BOOKING);
        signOut();

        // The public page has no signed-in user: its clinic comes from the link (PublicLinkRequestClinic).
        PublicInfo info = tenancy.callAs(practice, () -> booking.info(token));

        assertThat(info.timeZone()).isEqualTo("Africa/Casablanca");
        assertThat(info.maxDaysAhead()).isEqualTo(30);
    }
}
