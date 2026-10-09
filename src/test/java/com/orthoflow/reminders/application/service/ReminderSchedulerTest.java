package com.orthoflow.reminders.application.service;

import com.orthoflow.common.tenancy.Tenancy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/** A clinic's reminder goes out at its own hour, the number of days before the visit it chose. */
class ReminderSchedulerTest {

    private static final ZoneId ZONE = ZoneId.of("Africa/Casablanca");
    private final UUID practice = UUID.randomUUID();
    private MessagingSettingsService settings;
    private ReminderService reminders;
    private ReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        settings = mock(MessagingSettingsService.class);
        reminders = mock(ReminderService.class);
        Tenancy tenancy = mock(Tenancy.class);
        doAnswer(call -> {
            ((Runnable) call.getArgument(1)).run();
            return null;
        }).when(tenancy).runAs(any(), any());
        scheduler = new ReminderScheduler(mock(JdbcTemplate.class), id -> ZONE, settings, reminders, tenancy);
    }

    private void clinicSends(int hour, int daysBefore) {
        when(settings.get(practice)).thenReturn(new MessagingSettingsService.Settings(true, hour, false, 2, false, 3, daysBefore));
    }

    private OffsetDateTime at(int hour) {
        return LocalDate.of(2030, 3, 1).atTime(hour, 5).atZone(ZONE).toOffsetDateTime();
    }

    @Test
    void theReminderIsQueuedForTheVisitsThatManyDaysAway() {
        clinicSends(17, 3);

        scheduler.runFor(practice, at(17));

        verify(reminders).queueAppointmentReminders(practice, LocalDate.of(2030, 3, 4));
    }

    @Test
    void zeroDaysMeansTheMorningOfTheVisitAndOneKeepsTheDayBefore() {
        clinicSends(8, 0);
        scheduler.runFor(practice, at(8));
        verify(reminders).queueAppointmentReminders(practice, LocalDate.of(2030, 3, 1));

        clinicSends(17, 1);
        scheduler.runFor(practice, at(17));
        verify(reminders).queueAppointmentReminders(practice, LocalDate.of(2030, 3, 2));
    }

    @Test
    void nothingIsQueuedOutsideTheClinicsChosenHour() {
        clinicSends(17, 3);

        scheduler.runFor(practice, at(9));

        verify(reminders, never()).queueAppointmentReminders(any(), any());
        verify(reminders, never()).queueInstalmentReminders(any(), any(), anyInt());
    }
}
