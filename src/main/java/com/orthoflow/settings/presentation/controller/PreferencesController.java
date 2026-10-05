package com.orthoflow.settings.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.settings.application.service.StatusColorService;
import com.orthoflow.settings.application.service.UserPreferencesService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** One's own interface preferences, and the clinic's agenda status colours. */
@RestController
@RequiredArgsConstructor
public class PreferencesController {

    private final UserPreferencesService preferences;
    private final StatusColorService statusColors;
    private final CurrentUserProvider currentUser;

    @GetMapping("/me/preferences")
    public Map<String, Object> mine() {
        return preferences.get(currentUser.requireUserId());
    }

    @PutMapping("/me/preferences")
    public Map<String, Object> saveMine(@RequestBody Map<String, Object> prefs) {
        return preferences.put(currentUser.requireUserId(), prefs);
    }

    @GetMapping("/settings/practice/status-colors")
    public Map<String, String> colors() {
        return statusColors.get(currentUser.requirePracticeId());
    }

    @PutMapping("/settings/practice/status-colors")
    public Map<String, String> saveColors(@RequestBody Map<String, String> colors) {
        return statusColors.put(currentUser.requirePracticeId(), colors);
    }
}
