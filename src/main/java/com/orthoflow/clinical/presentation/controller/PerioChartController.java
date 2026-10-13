package com.orthoflow.clinical.presentation.controller;

import com.orthoflow.clinical.application.dto.PerioChartDtos.Chart;
import com.orthoflow.clinical.application.dto.PerioChartDtos.ChartListItem;
import com.orthoflow.clinical.application.dto.PerioChartDtos.CreateChartRequest;
import com.orthoflow.clinical.application.dto.PerioChartDtos.SaveChartRequest;
import com.orthoflow.clinical.application.service.PerioChartService;
import com.orthoflow.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Periodontal charts (six-point probing). Under {@code /clinical-record} so that the clinical-role rule
 * covers them: a front-desk account can neither read nor write a probing chart.
 */
@RestController
@RequestMapping("/patients/{patientId}/clinical-record/perio-charts")
@RequiredArgsConstructor
public class PerioChartController {

    private final PerioChartService service;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<ChartListItem> list(@PathVariable UUID patientId,
                                    @RequestParam(defaultValue = "false") boolean deleted) {
        return deleted ? service.listDeleted(patientId) : service.list(patientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Chart create(@PathVariable UUID patientId, @Valid @RequestBody CreateChartRequest request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), patientId, request);
    }

    @GetMapping("/{chartId}")
    public Chart get(@PathVariable UUID patientId, @PathVariable UUID chartId) {
        return service.get(patientId, chartId);
    }

    @PutMapping("/{chartId}")
    public Chart save(@PathVariable UUID patientId, @PathVariable UUID chartId,
                      @Valid @RequestBody SaveChartRequest request) {
        return service.save(currentUser.requireUserId(), patientId, chartId, request);
    }

    @DeleteMapping("/{chartId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID patientId, @PathVariable UUID chartId) {
        service.delete(currentUser.requirePracticeId(), patientId, chartId);
    }

    @PostMapping("/{chartId}/restore")
    public Chart restore(@PathVariable UUID patientId, @PathVariable UUID chartId) {
        return service.restore(currentUser.requirePracticeId(), patientId, chartId);
    }
}
