package com.orthoflow.clinical.application.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateToothStateRequest {

    @NotBlank
    private String status;

    private String notes;

    /** Which UI surface made the change: '2d' or '3d'; older rows also carry '3d_top' and its like. */
    @NotBlank
    private String source;
}
