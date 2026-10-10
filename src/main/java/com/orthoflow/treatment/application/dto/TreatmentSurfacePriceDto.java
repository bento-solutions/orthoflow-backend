package com.orthoflow.treatment.application.dto;

import com.orthoflow.treatment.domain.model.TreatmentSurfacePrice;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

/** One line of a treatment's tariff by number of faces; used to read and to write it. */
@Schema(name = "TreatmentSurfacePrice")
public record TreatmentSurfacePriceDto(
        @Min(1) @Max(5) int surfaceCount,
        @NotNull @PositiveOrZero BigDecimal price) {

    public static TreatmentSurfacePriceDto from(TreatmentSurfacePrice p) {
        return new TreatmentSurfacePriceDto(p.getSurfaceCount(), p.getPrice());
    }
}
