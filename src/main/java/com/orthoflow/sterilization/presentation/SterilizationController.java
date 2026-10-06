package com.orthoflow.sterilization.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.export.application.dto.ExportFormat;
import com.orthoflow.export.application.service.ExportService;
import com.orthoflow.sterilization.application.dto.SterilizationDtos.*;
import com.orthoflow.sterilization.application.service.CycleService;
import com.orthoflow.sterilization.application.service.LabelService;
import com.orthoflow.sterilization.application.service.SterilizationService;
import com.orthoflow.sterilization.application.service.TraceabilityService;
import com.orthoflow.sterilization.domain.model.SterilizationCycle.ControlResult;
import com.orthoflow.sterilization.domain.model.SterilizationItem.Kind;
import com.orthoflow.sterilization.domain.model.SterilizationItem.State;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Sterilization traceability. Everything here is STERILIZATION_MANAGE (SecurityConfig). */
@RestController
@RequestMapping("/sterilization")
@RequiredArgsConstructor
public class SterilizationController {

    private final SterilizationService service;
    private final CycleService cycleService;
    private final TraceabilityService traceability;
    private final LabelService labels;
    private final ExportService exportService;
    private final CurrentUserProvider currentUser;
    private final PracticeZone practiceZone;

    // ── Items ──
    @GetMapping("/items")
    public List<ItemView> items(@RequestParam(required = false) State state, @RequestParam(required = false) Kind kind,
                                @RequestParam(required = false) String search, @RequestParam(defaultValue = "false") boolean includeRetired) {
        return service.list(currentUser.requirePracticeId(), state, kind, search, includeRetired);
    }

    @PostMapping("/items")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemView create(@Valid @RequestBody ItemRequest request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @GetMapping("/items/{id}")
    public ItemView item(@PathVariable UUID id) {
        return service.get(currentUser.requirePracticeId(), id);
    }

    @PutMapping("/items/{id}")
    public ItemView update(@PathVariable UUID id, @Valid @RequestBody ItemUpdate request) {
        return service.update(currentUser.requirePracticeId(), id, request);
    }

    @PostMapping("/items/{id}/retire")
    public ItemView retire(@PathVariable UUID id, @RequestBody(required = false) RetireRequest request) {
        return service.retire(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request == null ? null : request.reason());
    }

    @GetMapping("/scan")
    public ItemView scan(@RequestParam String code) {
        return service.scan(currentUser.requirePracticeId(), code);
    }

    @PostMapping("/items/{id}/use")
    public ItemView use(@PathVariable UUID id, @Valid @RequestBody UseRequest request) {
        return service.use(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }

    @PostMapping("/items/{id}/clean")
    public ItemView clean(@PathVariable UUID id) {
        return service.sendToCleaning(currentUser.requirePracticeId(), currentUser.requireUserId(), id);
    }

    @PostMapping("/items/{id}/lubricate")
    public ItemView lubricate(@PathVariable UUID id, @RequestBody(required = false) LubricateRequest request) {
        return service.lubricate(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request == null ? null : request.product());
    }

    @GetMapping("/dashboard")
    public Dashboard dashboard(@RequestParam(required = false) Integer shelfLifeDays) {
        return service.dashboard(currentUser.requirePracticeId(), shelfLifeDays);
    }

    @GetMapping("/labels")
    public ResponseEntity<byte[]> labels(@RequestParam List<UUID> itemId, @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        return exportService.download(labels.sheet(practice, itemId, lang), ExportFormat.PDF, "etiquettes-sterilisation");
    }

    // ── Autoclaves ──
    @GetMapping("/autoclaves")
    public List<AutoclaveView> autoclaves() {
        return cycleService.listAutoclaves(currentUser.requirePracticeId());
    }

    @PostMapping("/autoclaves")
    @ResponseStatus(HttpStatus.CREATED)
    public AutoclaveView createAutoclave(@Valid @RequestBody AutoclaveRequest request) {
        return cycleService.createAutoclave(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/autoclaves/{id}")
    public AutoclaveView updateAutoclave(@PathVariable UUID id, @Valid @RequestBody AutoclaveRequest request) {
        return cycleService.updateAutoclave(currentUser.requirePracticeId(), id, request);
    }

    // ── Cycles ──
    @GetMapping("/cycles")
    public List<CycleSummary> cycles(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                     @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                     @RequestParam(required = false) UUID autoclaveId,
                                     @RequestParam(required = false) ControlResult result) {
        UUID practice = currentUser.requirePracticeId();
        return cycleService.list(practice, from, to, autoclaveId, result, practiceZone.of(practice));
    }

    @PostMapping("/cycles")
    @ResponseStatus(HttpStatus.CREATED)
    public CycleView createCycle(@Valid @RequestBody CycleRequest request) {
        return cycleService.create(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @GetMapping("/cycles/{id}")
    public CycleView cycle(@PathVariable UUID id) {
        return cycleService.get(currentUser.requirePracticeId(), id);
    }

    @PostMapping("/cycles/{id}/control")
    public CycleView control(@PathVariable UUID id, @Valid @RequestBody ControlRequest request) {
        return cycleService.recordControl(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }

    @GetMapping("/cycles/{id}/exposure")
    public Exposure exposure(@PathVariable UUID id) {
        return cycleService.exposure(currentUser.requirePracticeId(), id);
    }

    // ── Traceability ──
    @GetMapping("/traceability/patients/{patientId}")
    public List<TraceEntry> forPatient(@PathVariable UUID patientId) {
        return traceability.forPatient(currentUser.requirePracticeId(), patientId);
    }

    @GetMapping("/traceability/appointments/{appointmentId}")
    public List<TraceEntry> forAppointment(@PathVariable UUID appointmentId) {
        return traceability.forAppointment(currentUser.requirePracticeId(), appointmentId);
    }

    @GetMapping("/traceability/items/{itemId}")
    public List<TraceEntry> forItem(@PathVariable UUID itemId) {
        return traceability.forItem(currentUser.requirePracticeId(), itemId);
    }
}
