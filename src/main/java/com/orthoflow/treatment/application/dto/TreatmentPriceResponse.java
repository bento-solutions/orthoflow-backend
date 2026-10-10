package com.orthoflow.treatment.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.UUID;

/** What a treatment costs on a given part of a tooth, and what that was worked out from. */
@Schema(name = "TreatmentPrice")
public record TreatmentPriceResponse(
        UUID treatmentId,
        String surface,
        int faceCount,
        BigDecimal price,
        BigDecimal basePrice,
        /** BASE, or FACES_n when the clinic's n-face price applied. */
        String basis) {
}
