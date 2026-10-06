package com.orthoflow.booking.presentation;

import com.orthoflow.booking.application.dto.BookingDtos.*;
import com.orthoflow.booking.application.service.BookingService;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Staff side of online booking: the inbox of requests, and the booking settings. */
@RestController
@RequestMapping("/booking")
@RequiredArgsConstructor
public class BookingAdminController {

    private final BookingService service;
    private final CurrentUserProvider currentUser;

    @GetMapping("/settings")
    public Settings settings() {
        return service.settings(currentUser.requirePracticeId());
    }

    @PutMapping("/settings")
    public Settings saveSettings(@Valid @RequestBody Settings request) {
        return service.saveSettings(currentUser.requirePracticeId(), request);
    }

    @GetMapping("/requests")
    public List<RequestView> list(@RequestParam(required = false) String status) {
        return service.list(currentUser.requirePracticeId(), status);
    }

    @GetMapping("/requests/count")
    public Map<String, Long> count() {
        return Map.of("pending", service.pendingCount(currentUser.requirePracticeId()));
    }

    @PostMapping("/requests/{id}/confirm")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse confirm(@PathVariable UUID id, @RequestBody(required = false) Confirm request) {
        return service.confirm(currentUser.requirePracticeId(), currentUser.requireUserId(), id,
                request == null ? new Confirm(null, null, null, null, null) : request);
    }

    @PostMapping("/requests/{id}/decline")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decline(@PathVariable UUID id, @RequestBody(required = false) Decline request) {
        service.decline(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request == null ? null : request.reason());
    }
}
