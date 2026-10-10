package com.orthoflow.clinical.application.dto;

import lombok.Builder;

import java.util.List;

/** The gums today (latest assessment per region) and how they got there. */
@Builder
public record PeriodontalStatusResponse(
        List<PeriodontalAssessmentResponse> current,
        List<PeriodontalAssessmentResponse> history
) {}
