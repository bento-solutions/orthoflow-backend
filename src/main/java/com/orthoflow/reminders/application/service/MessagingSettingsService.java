package com.orthoflow.reminders.application.service;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** When the clinic talks to patients on its own: reminders, instalment notices, satisfaction surveys. All off until enabled. */
@Service
@RequiredArgsConstructor
public class MessagingSettingsService {

    @io.swagger.v3.oas.annotations.media.Schema(name = "MessagingSettings")
    public record Settings(boolean appointmentReminders, @Min(0) @Max(23) int reminderSendHour, boolean instalmentReminders,
                           @Min(0) @Max(30) int instalmentDaysBefore, boolean surveyEnabled, @Min(0) @Max(168) int surveyDelayHours) {
    }

    private final JdbcTemplate jdbc;

    @Transactional
    public Settings get(UUID practiceId) {
        jdbc.update("INSERT INTO messaging_settings (practice_id) VALUES (?) ON CONFLICT DO NOTHING", practiceId);
        return jdbc.queryForObject("SELECT appointment_reminders, reminder_send_hour, instalment_reminders, instalment_days_before, survey_enabled, survey_delay_hours FROM messaging_settings WHERE practice_id = ?",
                (rs, i) -> new Settings(rs.getBoolean(1), rs.getInt(2), rs.getBoolean(3), rs.getInt(4), rs.getBoolean(5), rs.getInt(6)), practiceId);
    }

    @Transactional
    public Settings put(UUID practiceId, Settings s) {
        get(practiceId);
        jdbc.update("""
                UPDATE messaging_settings SET appointment_reminders = ?, reminder_send_hour = ?, instalment_reminders = ?,
                       instalment_days_before = ?, survey_enabled = ?, survey_delay_hours = ?, updated_at = NOW() WHERE practice_id = ?
                """, s.appointmentReminders(), s.reminderSendHour(), s.instalmentReminders(), s.instalmentDaysBefore(),
                s.surveyEnabled(), s.surveyDelayHours(), practiceId);
        return get(practiceId);
    }
}
