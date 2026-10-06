package com.orthoflow.billing.application.dto;

import com.orthoflow.billing.domain.model.InvoiceStatus;
import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class InvoiceResponse {
    private UUID id;
    private UUID practiceId;
    private UUID patientId;
    private UUID practitionerId;
    private String invoiceNumber;
    private InvoiceStatus status;
    private LocalDate issueDate;
    private LocalDate dueDate;
    private String currency;
    private BigDecimal subtotal;
    private BigDecimal discountAmount;
    private BigDecimal taxAmount;
    private BigDecimal total;
    private BigDecimal amountPaid;
    private BigDecimal balanceDue;
    private String regionCode;
    private OffsetDateTime createdAt;
    private List<InvoiceLineResponse> lines;
    private List<PaymentResponse> payments;
}
