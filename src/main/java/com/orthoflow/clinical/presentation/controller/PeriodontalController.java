package com.orthoflow.clinical.presentation.controller;

import com.orthoflow.clinical.application.dto.PeriodontalAssessmentResponse;
import com.orthoflow.clinical.application.dto.PeriodontalStatusResponse;
import com.orthoflow.clinical.application.dto.RecordPeriodontalRequest;
import com.orthoflow.clinical.application.service.PeriodontalService;
import com.orthoflow.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Gum status. Sits under {@code /clinical-record} so the existing clinical-role
 * rule covers it: an assistant cannot read or write a periodontal diagnosis.
 */
@RestController
@RequestMapping("/patients/{patientId}/clinical-record/periodontal")
@RequiredArgsConstructor
public class PeriodontalController {

    private final PeriodontalService periodontalService;
    private final CurrentUserProvider currentUserProvider;

    @GetMapping
    public PeriodontalStatusResponse status(@PathVariable UUID patientId) {
        return periodontalService.status(patientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PeriodontalAssessmentResponse record(
            @PathVariable UUID patientId,
            @Valid @RequestBody RecordPeriodontalRequest request) {
        return periodontalService.record(patientId, request, currentUserProvider.requireUserId());
    }
}
