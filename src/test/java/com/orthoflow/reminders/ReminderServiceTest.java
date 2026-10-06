package com.orthoflow.reminders;

import com.orthoflow.billing.infrastructure.adapter.persistence.PaymentPlanJpaRepository;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.application.service.MessageService;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.reminders.application.service.ChannelPicker;
import com.orthoflow.reminders.application.service.ReminderService;
import com.orthoflow.scheduling.domain.model.Appointment;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.AppointmentJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/** Tomorrow's reminders go to patients who agreed, in their language, once. */
class ReminderServiceTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    private final UUID practice = UUID.randomUUID();
    private final LocalDate tomorrow = LocalDate.of(2030, 3, 5);

    private AppointmentJpaRepository appointments;
    private PatientLookup patients;
    private ChannelPicker picker;
    private MessageService messages;
    private ReminderService service;

    @BeforeEach
    void setUp() {
        appointments = mock(AppointmentJpaRepository.class);
        patients = mock(PatientLookup.class);
        picker = mock(ChannelPicker.class);
        messages = mock(MessageService.class);
        service = new ReminderService(appointments, mock(PaymentPlanJpaRepository.class), patients, picker, messages,
                mock(PublicLinkService.class), id -> ZONE, mock(JdbcTemplate.class));
    }

    private Appointment visit(UUID patient, String time) {
        return Appointment.builder().id(UUID.randomUUID()).practiceId(practice).patientId(patient)
                .dateTime(tomorrow.atTime(java.time.LocalTime.parse(time)).atZone(ZONE).toOffsetDateTime()).status(AppointmentStatus.SCHEDULED).build();
    }

    private PatientSummary patient(UUID id, String language) {
        return new PatientSummary(id, "Sara", "Benziane", "s@x.ma", "0662", null, language);
    }

    @Test
    void eachVisitGetsOneReminderInThePatientsLanguageOverTheirChosenChannel() {
        UUID sara = UUID.randomUUID();
        Appointment a = visit(sara, "10:30");
        when(appointments.inStatuses(eq(practice), any(), any(), any())).thenReturn(List.of(a));
        when(patients.findSummaries(anyList())).thenReturn(Map.of(sara, patient(sara, "ar")));
        when(picker.pick(any())).thenReturn(Optional.of(MessageChannel.WHATSAPP));

        int queued = service.queueAppointmentReminders(practice, tomorrow);

        assertThat(queued).isEqualTo(1);
        ArgumentCaptor<OutgoingMessage> sent = ArgumentCaptor.forClass(OutgoingMessage.class);
        verify(messages).enqueue(sent.capture());
        OutgoingMessage m = sent.getValue();
        assertThat(m.getPurpose()).isEqualTo(MessagePurpose.APPOINTMENT_REMINDER);
        assertThat(m.getChannel()).isEqualTo(MessageChannel.WHATSAPP);
        assertThat(m.getLanguage()).isEqualTo("ar");
        assertThat(m.getVariables()).containsEntry("date", "05/03/2030").containsEntry("time", "10:30");
        assertThat(m.getDedupeKey()).isEqualTo("reminder:" + a.getId() + ":" + tomorrow);
        assertThat(m.getRelatedId()).isEqualTo(a.getId());
    }

    @Test
    void aPatientWhoAgreedToNothingIsSkippedWithoutQueuingAMessageThatWouldBeCancelled() {
        UUID sara = UUID.randomUUID();
        when(appointments.inStatuses(eq(practice), any(), any(), any())).thenReturn(List.of(visit(sara, "09:00")));
        when(patients.findSummaries(anyList())).thenReturn(Map.of(sara, patient(sara, "fr")));
        when(picker.pick(any())).thenReturn(Optional.empty());

        assertThat(service.queueAppointmentReminders(practice, tomorrow)).isZero();
        verify(messages, never()).enqueue(any());
    }

    @Test
    void runningTheJobTwiceSendsNothingTwice() {
        UUID sara = UUID.randomUUID();
        Appointment a = visit(sara, "09:00");
        when(appointments.inStatuses(eq(practice), any(), any(), any())).thenReturn(List.of(a));
        when(patients.findSummaries(anyList())).thenReturn(Map.of(sara, patient(sara, "fr")));
        when(picker.pick(any())).thenReturn(Optional.of(MessageChannel.EMAIL));
        when(messages.alreadyQueued("reminder:" + a.getId() + ":" + tomorrow)).thenReturn(false, true);

        assertThat(service.queueAppointmentReminders(practice, tomorrow)).isEqualTo(1);
        assertThat(service.queueAppointmentReminders(practice, tomorrow)).isZero();
        verify(messages, times(1)).enqueue(any());
    }

    @Test
    void recallRemindersAreOnePerPatientPerMonth() {
        UUID sara = UUID.randomUUID();
        when(patients.findSummaries(anyList())).thenReturn(Map.of(sara, patient(sara, "fr")));
        when(picker.pick(any())).thenReturn(Optional.of(MessageChannel.WHATSAPP));
        when(messages.alreadyQueued(any())).thenReturn(false, true);

        assertThat(service.sendRecallReminders(practice, UUID.randomUUID(), List.of(sara))).isEqualTo(1);
        assertThat(service.sendRecallReminders(practice, UUID.randomUUID(), List.of(sara))).isZero();
    }
}
