package com.orthoflow.scheduling.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.scheduling.application.dto.AgendaDtos.*;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.application.service.WaitingListService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/scheduling/waiting-list")
@RequiredArgsConstructor
public class WaitingListController {

    private final WaitingListService service;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<WaitingEntryResponse> open() {
        return service.open(currentUser.requirePracticeId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WaitingEntryResponse create(@Valid @RequestBody WaitingEntryRequest request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PutMapping("/{id}")
    public WaitingEntryResponse update(@PathVariable UUID id, @Valid @RequestBody WaitingEntryRequest request) {
        return service.update(currentUser.requirePracticeId(), id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) {
        service.cancel(currentUser.requirePracticeId(), id);
    }

    /** Books the entry into a slot (the drop target of drag-and-drop onto the grid). */
    @PostMapping("/{id}/schedule")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse schedule(@PathVariable UUID id, @Valid @RequestBody ScheduleFromWaiting request) {
        return service.schedule(currentUser.requirePracticeId(), id, request);
    }
}
