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

    /**
     * Pass `from`/`to` (ISO-8601 offset date-times) to load only the window a
     * screen actually needs — the day/month/year calendar views used to
     * fetch every appointment the clinic has ever had and filter client-side
     * (audit II.8/VI.4). Omitting them keeps the old full-history behaviour
     * for any caller not yet updated. Any of practitionerId, chairId, status
     * and typeId narrows the window further (the agenda's filters).
     */
    @GetMapping
    public List<AppointmentResponse> getAllAppointments(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(required = false) UUID practitionerId,
            @RequestParam(required = false) UUID chairId,
            @RequestParam(required = false) AppointmentStatus status,
            @RequestParam(required = false) UUID typeId) {
        boolean filtered = practitionerId != null || chairId != null || status != null || typeId != null;
        if (filtered) {
            return appointmentService.agenda(currentUser.requirePracticeId(),
                    from != null ? from : OffsetDateTime.now().minusYears(10),
                    to != null ? to : OffsetDateTime.now().plusYears(10),
                    practitionerId, chairId, status, typeId);
        }
        if (from != null && to != null) {
            return appointmentService.getAppointmentsInRange(from, to);
        }
        return appointmentService.getAllAppointments();
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
