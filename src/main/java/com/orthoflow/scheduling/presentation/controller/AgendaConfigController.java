package com.orthoflow.scheduling.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.scheduling.application.dto.AgendaDtos.*;
import com.orthoflow.scheduling.application.service.AgendaConfigService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Appointment types, waiting rooms, absences and calendar events. Reads are for
 * anyone who can see the agenda; types and rooms change under Settings, while
 * absences and events are the receptionist's own business. Authorities are in
 * SecurityConfig.
 */
@RestController
@RequestMapping("/scheduling")
@RequiredArgsConstructor
public class AgendaConfigController {

    private final AgendaConfigService service;
    private final CurrentUserProvider currentUser;

    @GetMapping("/appointment-types")
    public List<TypeResponse> types(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.types(currentUser.requirePracticeId(), includeInactive);
    }

    @PostMapping("/appointment-types")
    @ResponseStatus(HttpStatus.CREATED)
    public TypeResponse createType(@Valid @RequestBody TypeRequest request) {
        return service.createType(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/appointment-types/{id}")
    public TypeResponse updateType(@PathVariable UUID id, @Valid @RequestBody TypeRequest request) {
        return service.updateType(currentUser.requirePracticeId(), id, request);
    }

    @GetMapping("/waiting-rooms")
    public List<RoomResponse> rooms(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.rooms(currentUser.requirePracticeId(), includeInactive);
    }

    @PostMapping("/waiting-rooms")
    @ResponseStatus(HttpStatus.CREATED)
    public RoomResponse createRoom(@Valid @RequestBody RoomRequest request) {
        return service.createRoom(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/waiting-rooms/{id}")
    public RoomResponse updateRoom(@PathVariable UUID id, @Valid @RequestBody RoomRequest request) {
        return service.updateRoom(currentUser.requirePracticeId(), id, request);
    }

    @GetMapping("/absences")
    public List<AbsenceResponse> absences(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return service.absences(currentUser.requirePracticeId(), from, to);
    }

    @PostMapping("/absences")
    @ResponseStatus(HttpStatus.CREATED)
    public AbsenceResponse createAbsence(@Valid @RequestBody AbsenceRequest request) {
        return service.createAbsence(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PutMapping("/absences/{id}")
    public AbsenceResponse updateAbsence(@PathVariable UUID id, @Valid @RequestBody AbsenceRequest request) {
        return service.updateAbsence(currentUser.requirePracticeId(), id, request);
    }

    @DeleteMapping("/absences/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAbsence(@PathVariable UUID id) {
        service.deleteAbsence(currentUser.requirePracticeId(), id);
    }

    @GetMapping("/events")
    public List<EventResponse> events(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return service.events(currentUser.requirePracticeId(), from, to);
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    public EventResponse createEvent(@Valid @RequestBody EventRequest request) {
        return service.createEvent(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PutMapping("/events/{id}")
    public EventResponse updateEvent(@PathVariable UUID id, @Valid @RequestBody EventRequest request) {
        return service.updateEvent(currentUser.requirePracticeId(), id, request);
    }

    @DeleteMapping("/events/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteEvent(@PathVariable UUID id) {
        service.deleteEvent(currentUser.requirePracticeId(), id);
    }
}
