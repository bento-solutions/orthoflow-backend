package com.orthoflow.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class RecordPeriodontalRequest {

    /** WHOLE_MOUTH or one of the six sextants. */
    @NotBlank
    @Size(max = 16)
    private String region;

    /** HEALTHY | GINGIVITIS | PERIODONTITIS. */
    @NotBlank
    @Size(max = 16)
    private String condition;

    /** 1-4, periodontitis only. */
    private Integer stage;

    private String note;

    /** Defaults to today; not in the future. */
    private LocalDate assessedOn;

    @NotBlank
    @Size(max = 20)
    private String source;
}
