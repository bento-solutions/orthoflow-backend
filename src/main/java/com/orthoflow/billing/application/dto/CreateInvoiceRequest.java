package com.orthoflow.billing.application.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.util.List;
import java.util.UUID;

@Data
public class CreateInvoiceRequest {

    @NotNull
    private UUID practiceId;

    @NotNull
    private UUID patientId;

    private UUID treatmentPlanId;

    /** Who performed the work. Defaults to the patient's primary practitioner when omitted. */
    private UUID practitionerId;

    @NotNull
    private String currency;

    @NotNull
    private String regionCode;

    private String notes;

    @NotEmpty(message = "An invoice must have at least one line")
    @Valid
    private List<InvoiceLineRequest> lines;
}
