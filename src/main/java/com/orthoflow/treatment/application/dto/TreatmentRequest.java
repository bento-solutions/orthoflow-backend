package com.orthoflow.treatment.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * Request DTO for creating/updating a treatment catalog entry. Deliberately
 * excludes `id`, `createdAt`, `updatedAt` — server-owned. `code` uniqueness
 * is still enforced by the DB constraint, surfaced as before (no new
 * pre-check added here).
 */
@Getter
@Setter
public class TreatmentRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String code;

    private String description;

    @NotNull
    @PositiveOrZero
    private BigDecimal basePrice;

    private boolean active = true;

    private String category;

    @Positive
    private Integer durationMinutes;

    @jakarta.validation.constraints.Size(max = 30)
    private String actCode;

    @PositiveOrZero
    private BigDecimal actCoefficient;

    @Valid
    private List<TreatmentConsumableRequest> consumables;

    /** The tariff by number of faces; null leaves it as it is, an empty list clears it. */
    @Valid
    private List<TreatmentSurfacePriceDto> surfacePrices;
}
