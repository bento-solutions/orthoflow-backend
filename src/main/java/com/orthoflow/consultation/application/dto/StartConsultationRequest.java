package com.orthoflow.consultation.application.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
public class StartConsultationRequest {

    @NotNull
    private UUID patientId;

    @Size(max = 12)
    private String locale;

    /**
     * The doctor's attestation that the patient was told the whole conversation
     * is transcribed by an outside service and kept in their file. Must be true:
     * the recording is not started without it. The software cannot confirm the
     * patient was told — it can only make skipping the question impossible, and
     * record when the doctor said they had.
     */
    private boolean patientInformed;
}
