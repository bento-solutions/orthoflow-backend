package com.orthoflow.scheduling.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.export.application.service.ExportService;
import com.orthoflow.scheduling.application.dto.AppointmentRequest;
import com.orthoflow.scheduling.application.dto.AppointmentResponse;
import com.orthoflow.scheduling.application.service.AppointmentService;
import com.orthoflow.scheduling.application.service.DailySheetService;
import com.orthoflow.scheduling.domain.model.AppointmentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/appointments")
@RequiredArgsConstructor
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final DailySheetService dailySheetService;
    private final ExportService exportService;
    private final CurrentUserProvider currentUser;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse createAppointment(@RequestBody AppointmentRequest request) {
        return appointmentService.createAppointment(request);
    }

    /** The longest window one request may ask for: a year view and its margins. */
    static final java.time.Duration MAX_WINDOW = java.time.Duration.ofDays(400);

    /**
     * The appointments in a window, `from`/`to` as ISO-8601 offset date-times; any of practitionerId,
     * chairId, status and typeId narrows it (the agenda's filters). The window is always bounded
     * (audit P2): omitting it gives the agenda's default (three months back, seven ahead) rather than
     * the clinic's whole history, and a window over {@link #MAX_WINDOW} is refused.
     */
    @GetMapping
    public List<AppointmentResponse> getAllAppointments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID practitionerId,
            @RequestParam(required = false) UUID chairId,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) UUID typeId) {
        OffsetDateTime start = from != null ? from : to != null ? to.minusMonths(10) : OffsetDateTime.now().minusMonths(3);
        OffsetDateTime end = to != null ? to : start.plusMonths(10);
        if (!end.isAfter(start)) {
            throw new com.orthoflow.common.exception.ValidationException("'to' must be after 'from'");
        }
        if (java.time.Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new com.orthoflow.common.exception.ValidationException("Ask for at most " + MAX_WINDOW.toDays() + " days of appointments at a time");
        }
        boolean filtered = practitionerId != null || chairId != null || status != null || typeId != null;
        if (filtered) {
            return appointmentService.agenda(currentUser.requirePracticeId(), start, end, practitionerId, chairId, status, typeId);
        }
        return appointmentService.getAppointmentsInRange(start, end);
    }

    /** The printable list of a day: {@code ?format=pdf|xlsx|csv}, per practitioner or for everyone. */
    @GetMapping("/daily-sheet")
    public ResponseEntity<byte[]> dailySheet(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) UUID practitionerId,
            @RequestParam(defaultValue = "pdf") String format,
            @RequestParam(defaultValue = "fr") String lang) {
        UUID practiceId = currentUser.requirePracticeId();
        return exportService.respond(dailySheetService.build(practiceId, date, practitionerId, lang), format,
                "planning-" + date, practiceId, lang);
    }

    @GetMapping("/{id}")
    public AppointmentResponse getAppointmentById(@PathVariable UUID id) {
        return appointmentService.getAppointmentById(id);
    }

    @PutMapping("/{id}")
    public AppointmentResponse updateAppointment(@PathVariable UUID id, @RequestBody AppointmentRequest request) {
        return appointmentService.updateAppointment(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAppointment(@PathVariable UUID id) {
        appointmentService.deleteAppointment(id);
    }
}
