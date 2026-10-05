package com.orthoflow.scheduling.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.scheduling.application.dto.AgendaDtos.*;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.application.service.FrontDeskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** The waiting room and chair board, and the steps a patient moves through. */
@RestController
@RequestMapping("/front-desk")
@RequiredArgsConstructor
public class FrontDeskController {

    private final FrontDeskService service;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public FrontDesk board() {
        return service.board(currentUser.requirePracticeId());
    }

    @PostMapping("/{appointmentId}/check-in")
    public AppointmentResponse checkIn(@PathVariable UUID appointmentId, @RequestBody(required = false) CheckIn request) {
        return service.checkIn(currentUser.requirePracticeId(), appointmentId, request == null ? null : request.waitingRoomId());
    }

    @PostMapping("/walk-in")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse walkIn(@Valid @RequestBody WalkIn request) {
        return service.walkIn(currentUser.requirePracticeId(), request);
    }

    @PostMapping("/{appointmentId}/seat")
    public AppointmentResponse seat(@PathVariable UUID appointmentId, @Valid @RequestBody Seat request) {
        return service.seat(currentUser.requirePracticeId(), appointmentId, request.chairId());
    }

    @PostMapping("/{appointmentId}/finish")
    public AppointmentResponse finish(@PathVariable UUID appointmentId) {
        return service.finish(currentUser.requirePracticeId(), appointmentId);
    }

    @PostMapping("/{appointmentId}/back-to-waiting")
    public AppointmentResponse backToWaiting(@PathVariable UUID appointmentId) {
        return service.backToWaiting(currentUser.requirePracticeId(), appointmentId);
    }

    @PutMapping("/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@Valid @RequestBody Reorder request) {
        service.reorder(currentUser.requirePracticeId(), request.orderedIds());
    }
}
