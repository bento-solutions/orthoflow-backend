package com.orthoflow.surveys.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.surveys.application.service.SurveyService;
import com.orthoflow.surveys.application.service.SurveyService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class SurveyController {

    private final SurveyService service;
    private final CurrentUserProvider currentUser;

    // ── Public ──
    @GetMapping("/public/survey/{token}")
    public PublicInfo info(@PathVariable String token) {
        return service.info(token);
    }

    @PostMapping("/public/survey/{token}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Boolean> answer(@PathVariable String token, @Valid @RequestBody Answer answer) {
        service.answer(token, answer);
        return Map.of("received", true);
    }

    // ── Staff ──
    @GetMapping("/surveys")
    public Report report(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                         @RequestParam(required = false) Integer maxRating, @RequestParam(defaultValue = "false") boolean callMeOnly,
                         @RequestParam(defaultValue = "false") boolean unhandledOnly) {
        LocalDate end = to != null ? to : LocalDate.now();
        return service.report(currentUser.requirePracticeId(), from != null ? from : end.minusMonths(3), end, maxRating, callMeOnly, unhandledOnly);
    }

    @PostMapping("/surveys/{id}/handled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void handled(@PathVariable UUID id) {
        service.markHandled(currentUser.requirePracticeId(), currentUser.requireUserId(), id);
    }
}
