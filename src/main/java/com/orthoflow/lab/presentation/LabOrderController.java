package com.orthoflow.lab.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.lab.application.dto.LabDtos.*;
import com.orthoflow.lab.application.service.LabOrderService;
import com.orthoflow.lab.domain.model.LabStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Lab orders. Filters are plain query parameters so a filtered view can be kept in the URL. */
@RestController
@RequestMapping("/lab-orders")
@RequiredArgsConstructor
public class LabOrderController {

    private final LabOrderService service;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<View> list(@RequestParam(required = false) List<LabStatus> status,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueFrom,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate updatedFrom,
                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate updatedTo,
                           @RequestParam(defaultValue = "false") boolean urgentOnly,
                           @RequestParam(required = false) UUID patientId,
                           @RequestParam(required = false) UUID labId,
                           @RequestParam(defaultValue = "false") boolean overdueOnly,
                           @RequestParam(required = false) String search) {
        return service.list(new Filter(currentUser.requirePracticeId(), status, dueFrom, dueTo, updatedFrom, updatedTo, urgentOnly,
                patientId, labId, overdueOnly, search));
    }

    @GetMapping("/labs")
    public List<Lab> labs() {
        return service.labs();
    }

    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) {
        return service.get(currentUser.requirePracticeId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@Valid @RequestBody Request request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PutMapping("/{id}")
    public View update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return service.update(currentUser.requirePracticeId(), id, request);
    }

    @PostMapping("/{id}/status")
    public View transition(@PathVariable UUID id, @Valid @RequestBody Transition request) {
        return service.transition(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }

    @GetMapping("/{id}/document")
    public ResponseEntity<byte[]> document(@PathVariable UUID id, @RequestParam(defaultValue = "fr") String lang) {
        return ResponseEntity.ok().header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=\"bon-laboratoire.pdf\"").header("X-Content-Type-Options", "nosniff")
                .body(service.document(currentUser.requirePracticeId(), id, lang));
    }
}
