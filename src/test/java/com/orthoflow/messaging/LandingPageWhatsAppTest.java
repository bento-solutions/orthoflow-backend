package com.orthoflow.messaging;

import com.orthoflow.booking.application.dto.BookingDtos.Availability;
import com.orthoflow.booking.application.dto.BookingDtos.Confirm;
import com.orthoflow.booking.application.dto.BookingDtos.Settings;
import com.orthoflow.booking.application.dto.BookingDtos.Submit;
import com.orthoflow.booking.application.service.BookingService;
import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.messaging.application.service.LandingPageContacts;
import com.orthoflow.messaging.application.service.WhatsAppWebhookService;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import com.orthoflow.messaging.infrastructure.MessagingProperties.InboundFrom;
import com.orthoflow.patient.infrastructure.adapter.query.PatientDirectoryQuery;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clinic sends from the bento CRM's own WhatsApp number (one Baileys bridge, one
 * session). Of what that number receives, OrthoFlow keeps only the messages of people
 * who reached the clinic through its landing page; the CRM's other conversations are
 * dropped unread, and events of the bridge's other sessions are ignored.
 */
class LandingPageWhatsAppTest extends SpringDbTest {

    private static final String SESSION = "bento-crm-main";

    @Autowired
    private WhatsAppWebhookService webhook;
    @Autowired
    private MessagingProperties properties;
    @Autowired
    private LandingPageContacts contacts;
    @Autowired
    private Tenancy tenancy;
    @Autowired
    private BookingService booking;
    @Autowired
    private PublicLinkService links;
    @Autowired
    private PatientDirectoryQuery directory;

    private final MessagingProperties.WhatsApp saved = new MessagingProperties.WhatsApp();
    private JdbcTemplate jdbc;
    private UUID practice;

    @BeforeEach
    void sharedNumber() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        MessagingProperties.WhatsApp w = properties.getWhatsapp();
        saved.setSessionId(w.getSessionId());
        saved.setPracticeId(w.getPracticeId());
        saved.setInboundFrom(w.getInboundFrom());
        saved.setLandingPageMarker(w.getLandingPageMarker());
        w.setSessionId(SESSION);
        w.setPracticeId(practice);
        w.setInboundFrom(InboundFrom.LANDING_PAGE);
        w.setLandingPageMarker("#OrthoFlow");
    }

    @AfterEach
    void restore() {
        MessagingProperties.WhatsApp w = properties.getWhatsapp();
        w.setSessionId(saved.getSessionId());
        w.setPracticeId(saved.getPracticeId());
        w.setInboundFrom(saved.getInboundFrom());
        w.setLandingPageMarker(saved.getLandingPageMarker());
    }

    /** As the webhook runs: no signed-in user, across clinics (WebhookRequestClinic). */
    private void deliver(String session, String phone, String body) {
        String json = """
                {"events":[{"id":"%s","sessionId":"%s","type":"message.upsert",
                  "data":{"direction":"IN","wamid":"%s","phoneE164":"%s","body":"%s"}}]}
                """.formatted(UUID.randomUUID(), session, UUID.randomUUID(), phone, body);
        assertThat(tenancy.callAcrossClinics(() -> webhook.handle(json))).isEmpty();
    }

    private int stored(String phoneDigits) {
        return jdbc.queryForObject("SELECT count(*) FROM message_events WHERE practice_id = ? AND from_phone = ?", Integer.class,
                practice, phoneDigits);
    }

    @Test
    void aStrangerOnTheSharedNumberIsNotKept() {
        deliver(SESSION, "+212600000001", "Bonjour, je voudrais un devis pour mon site web");
        assertThat(stored("212600000001")).isZero();
    }

    @Test
    void anotherSessionOfTheBridgeIsIgnoredEvenFromAKnownContact() {
        contacts.record(practice, "0600000002", LandingPageContacts.Source.BOOKING);
        deliver("another-crm-account", "+212600000002", "1");
        assertThat(stored("212600000002")).isZero();
    }

    @Test
    void theLandingPagesClickToChatTextMakesTheSenderAContact() {
        deliver(SESSION, "+212600000003", "Bonjour #orthoflow, je voudrais un rendez-vous");
        deliver(SESSION, "+212600000003", "Mardi si possible");

        assertThat(stored("212600000003")).isEqualTo(2);
        signInTo(practice);
        assertThat(webhook.inbox(practice, true, true, 50)).hasSize(2).allMatch(r -> r.fromLandingPage());
    }

    @Test
    void aBookingMadeOnTheLandingPageLetsTheirRepliesThroughAndFlagsThePatient() {
        bookOnTheLandingPage(false);

        deliver(SESSION, "+212600000004", "Merci, à bientôt");
        assertThat(stored("212600000004")).isOne();

        UUID admin = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)",
                admin, admin + "@x.ma", practice);
        signInAs(admin, practice);
        UUID request = jdbc.queryForObject("SELECT id FROM booking_requests WHERE practice_id = ?", UUID.class, practice);
        assertThat(booking.list(practice, "PENDING")).singleElement().extracting(v -> v.source()).isEqualTo("LANDING_PAGE");
        booking.confirm(practice, admin, request, new Confirm(null, null, null, null, false));
        assertThat(directory.page(new PatientDirectoryQuery.Filter(practice, null, null, null, null, null, false, false, true),
                "name", false, 0, 10)).extracting(r -> r.lastName()).containsExactly("Idrissi");
    }

    @Test
    void anAutoConfirmedLandingPageBookingCreatesTheFlaggedPatientWithNobodySignedIn() {
        bookOnTheLandingPage(true);

        String channel = jdbc.queryForObject("SELECT acquisition_channel FROM patients WHERE practice_id = ? AND last_name = 'Idrissi'",
                String.class, practice);
        assertThat(channel).isEqualTo("LANDING_PAGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM appointments WHERE practice_id = ?", Integer.class, practice)).isOne();
    }

    @Test
    void anAutoConfirmedLandingPageBookingSendsTheConfirmationAndNothingElse() {
        bookOnTheLandingPage(true);

        assertThat(jdbc.queryForList("SELECT purpose FROM message_outbox WHERE practice_id = ?", String.class, practice))
                .containsExactly("BOOKING_CONFIRMED");
    }

    @Test
    void aLandingPageBookingWaitingForStaffIsAcknowledgedWithoutADate() {
        bookOnTheLandingPage(false);

        assertThat(jdbc.queryForList("SELECT purpose FROM message_outbox WHERE practice_id = ?", String.class, practice))
                .containsExactly("BOOKING_RECEIVED");
    }

    /** Sets up online booking, then books as the public page does: no signed-in user, the clinic from the link. */
    private void bookOnTheLandingPage(boolean autoConfirm) {
        signInTo(practice);
        booking.saveSettings(practice, new Settings(true, 0, 30, 30, autoConfirm));
        String token = links.sharedToken(practice, PublicLinkPurpose.BOOKING);
        UUID type = UUID.randomUUID();
        jdbc.update("INSERT INTO appointment_types (id, practice_id, code, name_fr, name_en, name_ar, bookable_online) VALUES (?, ?, 'CONS', 'Consultation', 'Consultation', 'استشارة', true)",
                type, practice);
        jdbc.update("INSERT INTO practitioners (id, practice_id, display_name) VALUES (?, ?, 'Dr Tazi')", UUID.randomUUID(), practice);
        for (int weekday = 1; weekday <= 7; weekday++) {
            jdbc.update("INSERT INTO practice_opening_hours (id, practice_id, weekday, open_time, close_time) VALUES (?, ?, ?, '09:00', '18:00')",
                    UUID.randomUUID(), practice, weekday);
        }
        signOut();

        ZoneId casablanca = ZoneId.of("Africa/Casablanca");
        OffsetDateTime slot = tenancy.callAs(practice, () -> {
            Availability free = booking.availability(token, type, null, LocalDate.now(casablanca).plusDays(1), 14);
            var day = free.days().entrySet().iterator().next();
            return day.getKey().atTime(LocalTime.parse(day.getValue().get(0))).atZone(casablanca).toOffsetDateTime();
        });
        tenancy.callAs(practice, () -> booking.submit(token, new Submit(type, null, slot, "Salma", "Idrissi", "06 00 00 00 04", null, null,
                null, "fr", true, null, "LANDING_PAGE")));
    }

    @Test
    void onANumberOfTheClinicsOwnEverySenderIsKeptAndTheInboxCanStillFilter() {
        properties.getWhatsapp().setInboundFrom(InboundFrom.ANY);
        deliver(SESSION, "+212600000005", "Bonjour");
        deliver(SESSION, "+212600000006", "Bonjour #OrthoFlow");

        signInTo(practice);
        assertThat(webhook.inbox(practice, true, false, 50)).hasSize(2);
        assertThat(webhook.inbox(practice, true, true, 50)).singleElement().extracting(r -> r.fromPhone()).isEqualTo("212600000006");
    }
}
