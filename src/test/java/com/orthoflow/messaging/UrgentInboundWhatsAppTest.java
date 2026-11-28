package com.orthoflow.messaging;

import com.orthoflow.booking.infrastructure.BookingProperties;
import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.messaging.application.service.LandingPageContacts;
import com.orthoflow.messaging.application.service.WhatsAppWebhookService;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import com.orthoflow.messaging.infrastructure.MessagingProperties.InboundFrom;
import com.orthoflow.patient.application.port.PatientRegistrar;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UrgentInboundWhatsAppTest extends SpringDbTest {

    private static final String SESSION = "bento-crm-main";

    @Autowired
    private WhatsAppWebhookService webhook;
    @Autowired
    private MessagingProperties properties;
    @Autowired
    private BookingProperties bookingProperties;
    @Autowired
    private LandingPageContacts contacts;
    @Autowired
    private PatientRegistrar patients;
    @Autowired
    private AppointmentJpaRepository appointments;
    @Autowired
    private Tenancy tenancy;

    private final MessagingProperties.WhatsApp saved = new MessagingProperties.WhatsApp();
    private String savedMarker;
    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID doctor;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        doctor = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id, active) "
                        + "VALUES (?, ?, 'x', 'Achraf', 'Ouajid', 'DOCTOR', ?, true)",
                doctor, doctor + "@ortho.ma", practice);
        MessagingProperties.WhatsApp w = properties.getWhatsapp();
        saved.setSessionId(w.getSessionId());
        saved.setPracticeId(w.getPracticeId());
        saved.setInboundFrom(w.getInboundFrom());
        saved.setLandingPageMarker(w.getLandingPageMarker());
        savedMarker = bookingProperties.getEmergencyMarker();
        w.setSessionId(SESSION);
        w.setPracticeId(practice);
        w.setInboundFrom(InboundFrom.LANDING_PAGE);
        bookingProperties.setEmergencyMarker("URGENT");
    }

    @AfterEach
    void restore() {
        MessagingProperties.WhatsApp w = properties.getWhatsapp();
        w.setSessionId(saved.getSessionId());
        w.setPracticeId(saved.getPracticeId());
        w.setInboundFrom(saved.getInboundFrom());
        w.setLandingPageMarker(saved.getLandingPageMarker());
        bookingProperties.setEmergencyMarker(savedMarker);
    }

    private void deliver(String phone, String body) {
        String json = """
                {"events":[{"id":"%s","sessionId":"%s","type":"message.upsert",
                  "data":{"direction":"IN","wamid":"%s","phoneE164":"%s","body":"%s"}}]}
                """.formatted(UUID.randomUUID(), SESSION, UUID.randomUUID(), phone, body);
        assertThat(tenancy.callAcrossClinics(() -> webhook.handle(json))).isEmpty();
    }

    @Test
    void urgentMarkerFromUnknownSenderRaisesAlertEvenOnSharedNumber() {
        deliver("+212600000111", "Bonjour, j'ai besoin d'un site web");
        deliver("+212600000222", "URGENT - Douleur aigue et appareil casse");

        signInTo(practice);
        var inbox = webhook.inbox(practice, true, false, true, 50);
        assertThat(inbox).hasSize(1);
        assertThat(inbox.getFirst().urgent()).isTrue();
        assertThat(inbox.getFirst().fromPhone()).isEqualTo("212600000222");
        assertThat(webhook.status(practice).unhandledUrgent()).isEqualTo(1);

        Integer alerts = jdbc.queryForObject(
                "SELECT count(*) FROM message_outbox WHERE practice_id = ? AND recipient_user_id = ? AND purpose = 'URGENT_ALERT'",
                Integer.class, practice, doctor);
        assertThat(alerts).isEqualTo(1);
    }

    @Test
    void urgentMessageIsNotSwallowedAsReminderReplyWhenPatientHasAppointment() {
        signInAs(doctor, practice);
        UUID patientId = patients.registerWalkIn("Amine", "Zahir", "+212600000333");
        UUID practitionerId = UUID.randomUUID();
        jdbc.update("INSERT INTO practitioners (id, practice_id, user_id, display_name, active) VALUES (?, ?, ?, 'Dr Achraf Ouajid', true)",
                practitionerId, practice, doctor);
        OffsetDateTime start = LocalDate.now(ZoneOffset.UTC).plusDays(1).atTime(10, 0).atOffset(ZoneOffset.UTC);
        UUID apptId = UUID.randomUUID();
        appointments.saveAndFlush(Appointment.builder()
                .id(apptId)
                .practiceId(practice)
                .patientId(patientId)
                .practitionerId(practitionerId)
                .dateTime(start)
                .durationMinutes(30)
                .type("CONSULTATION")
                .status(AppointmentStatus.SCHEDULED)
                .build());
        contacts.record(practice, "0600000333", LandingPageContacts.Source.BOOKING);

        deliver("+212600000333", "URGENT - bague 2 decollee");
        deliver("+212600000333", "Merci docteur");

        signInTo(practice);
        var inbox = webhook.inbox(practice, true, false, false, 50);
        assertThat(inbox).hasSize(2);
        // Unhandled urgent messages sort above normal chats.
        assertThat(inbox.getFirst().urgent()).isTrue();
        assertThat(inbox.getFirst().handledAt()).isNull();

        String status = jdbc.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class, apptId);
        assertThat(status).isEqualTo("SCHEDULED");
    }
}
