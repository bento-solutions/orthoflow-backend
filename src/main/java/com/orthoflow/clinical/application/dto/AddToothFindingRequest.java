package com.orthoflow.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Note what is absent: the caller does not supply the finding's {@code kind}.
 * It is derived server-side from {@link com.orthoflow.clinical.domain.model.FindingCatalog}
 * so a client cannot file "extraction required" as a mere observation.
 */
@Getter
@Setter
public class AddToothFindingRequest {

    /** Canonical code from the shared lexicon; rejected if unknown. */
    @NotBlank
    @Size(max = 48)
    private String findingCode;

    /** One surface, or up to five joined by hyphens ("mesial-occlusal-distal-buccal"); the column holds 48. */
    @Size(max = 48)
    private String surface;

    /** MILD | MODERATE | SEVERE, or null. */
    @Size(max = 16)
    private String severity;

    private String note;

    /** When it was done or found; null when unknown. Not in the future. */
    private LocalDate performedOn;

    /** THIS_CLINIC (default) or EXTERNAL. */
    @Size(max = 16)
    private String origin;

    /** Who did it, for external work. */
    @Size(max = 160)
    private String providerName;

    /** '2d', '3d_top', 'voice', 'manual'. */
    @NotBlank
    @Size(max = 20)
    private String source;

    /** The dictated examination this came from, when there was one. */
    private UUID sessionId;
}
