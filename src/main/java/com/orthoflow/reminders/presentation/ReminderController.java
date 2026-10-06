package com.orthoflow.reminders.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.reminders.application.service.MessagingSettingsService;
import com.orthoflow.reminders.application.service.ReminderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class ReminderController {

    public record Recipients(@NotEmpty List<UUID> patientIds) {
    }

    private final MessagingSettingsService settings;
    private final ReminderService reminders;
    private final CurrentUserProvider currentUser;

    @GetMapping("/settings/messaging")
    public MessagingSettingsService.Settings get() {
        return settings.get(currentUser.requirePracticeId());
    }

    @PutMapping("/settings/messaging")
    public MessagingSettingsService.Settings put(@Valid @RequestBody MessagingSettingsService.Settings request) {
        return settings.put(currentUser.requirePracticeId(), request);
    }

    /** "Send a reminder" on a recall list: queues one notice per patient who agreed to be contacted. */
    @PostMapping("/recalls/send-reminders")
    public Map<String, Integer> sendRecall(@Valid @RequestBody Recipients request) {
        return Map.of("queued", reminders.sendRecallReminders(currentUser.requirePracticeId(), currentUser.requireUserId(), request.patientIds()));
    }
}
