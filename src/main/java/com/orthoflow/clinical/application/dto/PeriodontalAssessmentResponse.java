package com.orthoflow.clinical.application.dto;

import lombok.Builder;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Builder
public record PeriodontalAssessmentResponse(
        UUID id,
        String region,
        String condition,
        Integer stage,
        String note,
        LocalDate assessedOn,
        String source,
        OffsetDateTime createdAt
) {}
