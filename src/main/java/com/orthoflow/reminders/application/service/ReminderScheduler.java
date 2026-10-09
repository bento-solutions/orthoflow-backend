package com.orthoflow.reminders.application.service;

import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.common.tenancy.PracticeZone;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

/**
 * Wakes every hour and, for each clinic, does what that clinic's own clock and
 * settings say is due: reminders at the hour it chose, in its own time zone;
 * surveys whenever their delay has passed. Nothing is sent from here — it only
 * queues, and the outbox sends. A failure in one clinic does not stop the next.
 */
@Component
public class ReminderScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReminderScheduler.class);

    private final JdbcTemplate jdbc;
    private final PracticeZone practiceZone;
    private final MessagingSettingsService settings;
    private final ReminderService reminders;
    private final Tenancy tenancy;

    public ReminderScheduler(JdbcTemplate jdbc, PracticeZone practiceZone, MessagingSettingsService settings, ReminderService reminders,
                             Tenancy tenancy) {
        this.tenancy = tenancy;
        this.jdbc = jdbc;
        this.practiceZone = practiceZone;
        this.settings = settings;
        this.reminders = reminders;
    }

    @Scheduled(cron = "${orthoflow.reminders.cron:0 5 * * * *}")
    public void run() {
        List<UUID> practices = jdbc.queryForList("SELECT id FROM practices WHERE active", UUID.class);
        for (UUID practiceId : practices) {
            try {
                runFor(practiceId, OffsetDateTime.now());
            } catch (RuntimeException e) {
                log.error("Scheduled messages failed for practice {}", practiceId, e);
            }
        }
    }

    /** One clinic's turn; separate from {@link #run()} so a given moment can be tested. */
    void runFor(UUID practiceId, OffsetDateTime now) {
        tenancy.runAs(practiceId, () -> queueFor(practiceId, now));
    }

    private void queueFor(UUID practiceId, OffsetDateTime now) {
        var s = settings.get(practiceId);
        ZoneId zone = practiceZone.of(practiceId);
        var local = now.atZoneSameInstant(zone);
        if (local.getHour() == s.reminderSendHour()) {
            LocalDate today = local.toLocalDate();
            if (s.appointmentReminders()) {
                reminders.queueAppointmentReminders(practiceId, today.plusDays(1));
            }
            if (s.instalmentReminders()) {
                reminders.queueInstalmentReminders(practiceId, today, s.instalmentDaysBefore());
            }
        }
        if (s.surveyEnabled()) {
            reminders.requestSurveys(practiceId, now, s.surveyDelayHours());
        }
    }
}
