package com.orthoflow.registration.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.registration.application.service.RegistrationService;
import com.orthoflow.registration.application.service.RegistrationService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Self-registration: the public form (token only) and the staff review of what came in. */
@RestController
@RequiredArgsConstructor
public class RegistrationController {

    private final RegistrationService service;
    private final CurrentUserProvider currentUser;

    // ── Public ──
    @GetMapping("/public/register/{token}")
    public PublicInfo info(@PathVariable String token) {
        return service.info(token);
    }

    @PostMapping("/public/register/{token}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Map<String, Boolean> submit(@PathVariable String token, @Valid @RequestBody Form form) {
        service.submit(token, form);
        return Map.of("received", true);
    }

    // ── Staff ──
    @GetMapping("/registrations/pending")
    public List<Pending> pending() {
        return service.pending(currentUser.requirePracticeId());
    }

    @GetMapping("/registrations/count")
    public Map<String, Long> count() {
        return Map.of("pending", service.pendingCount(currentUser.requirePracticeId()));
    }

    @PostMapping("/registrations/{id}/approve")
    public Map<String, UUID> approve(@PathVariable UUID id, @RequestBody(required = false) Approve request) {
        return Map.of("patientId", service.approve(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request));
    }

    @PostMapping("/registrations/{id}/reject")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        service.reject(currentUser.requirePracticeId(), currentUser.requireUserId(), id, body == null ? null : body.get("reason"));
    }

    @PostMapping("/patients/{patientId}/registration-invite")
    public Invite invite(@PathVariable UUID patientId) {
        return service.invite(currentUser.requirePracticeId(), currentUser.requireUserId(), patientId);
    }
}
