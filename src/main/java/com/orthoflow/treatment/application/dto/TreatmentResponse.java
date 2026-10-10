package com.orthoflow.treatment.application.dto;

import com.orthoflow.treatment.domain.model.Treatment;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public record TreatmentResponse(
        UUID id,
        String code,
        String name,
        BigDecimal basePrice,
        List<TreatmentConsumableResponse> consumables,
        OffsetDateTime createdAt,
        String category,
        Integer durationMinutes,
        boolean active,
        String actCode,
        BigDecimal actCoefficient,
        List<TreatmentSurfacePriceDto> surfacePrices
) {
    public static TreatmentResponse from(Treatment t) {
        if (t == null) return null;
        return new TreatmentResponse(
                t.getId(),
                t.getCode(),
                t.getName(),
                t.getBasePrice(),
                t.getConsumables() == null ? List.of() :
                        t.getConsumables().stream().map(TreatmentConsumableResponse::from).collect(Collectors.toList()),
                t.getCreatedAt(),
                t.getCategory(), t.getDurationMinutes(), t.isActive(), t.getActCode(), t.getActCoefficient(),
                t.getSurfacePrices() == null ? List.of() :
                        t.getSurfacePrices().stream()
                                .sorted(java.util.Comparator.comparingInt(p -> p.getSurfaceCount()))
                                .map(TreatmentSurfacePriceDto::from).collect(Collectors.toList())
        );
    }

    /** Shallow variant for nesting inside PatientTreatment/TreatmentInvoice
     * responses — those call sites don't render the consumable recipe, and
     * Treatment.consumables is LAZY, so skipping it here avoids an
     * open-session-in-view-dependent N+1 on every treatment session list. */
    public static TreatmentResponse shallow(Treatment t) {
        if (t == null) return null;
        return new TreatmentResponse(t.getId(), t.getCode(), t.getName(), t.getBasePrice(), List.of(), t.getCreatedAt(),
                t.getCategory(), t.getDurationMinutes(), t.isActive(), t.getActCode(), t.getActCoefficient(), List.of());
    }
}
