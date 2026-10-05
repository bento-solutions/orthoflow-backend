package com.orthoflow.billing.application.dto;

import com.orthoflow.billing.domain.model.InvoiceStatus;
import com.orthoflow.billing.domain.model.PaymentMethod;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** The patient account: money received, what it was applied to, and what is left. */
public final class ReceiptDtos {

    private ReceiptDtos() {
    }

    public record Allocation(@NotNull UUID invoiceId, @NotNull @DecimalMin("0.01") BigDecimal amount) {
    }

    public record ChequeInfo(@NotBlank @Size(max = 40) String number, @Size(max = 100) String bank,
                             @Size(max = 200) String drawerName, @NotNull LocalDate dueDate) {
    }

    /**
     * Money received. {@code allocations} names the invoices it pays; with
     * {@code autoAllocate} whatever is left goes to the patient's oldest unpaid
     * invoices; anything still left is kept as credit.
     */
    public record RecordReceipt(@NotNull @DecimalMin("0.01") BigDecimal amount, @NotNull PaymentMethod method,
                                LocalDate receiptDate, UUID practitionerId, @Size(max = 128) String reference,
                                String notes, @Valid List<Allocation> allocations, Boolean autoAllocate,
                                @Valid ChequeInfo cheque) {
    }

    public record ApplyCredit(@NotNull UUID invoiceId, @DecimalMin("0.01") BigDecimal amount) {
    }

    public record VoidReceipt(@NotBlank String reason) {
    }

    public record AllocationView(UUID paymentId, UUID invoiceId, String invoiceNumber, BigDecimal amount, LocalDate date) {
    }

    public record ReceiptView(UUID id, UUID patientId, BigDecimal amount, BigDecimal allocated, BigDecimal unallocated,
                              PaymentMethod method, LocalDate receiptDate, UUID practitionerId, String reference,
                              String notes, boolean voided, OffsetDateTime voidedAt, String voidReason, UUID chequeId,
                              List<AllocationView> allocations, OffsetDateTime createdAt) {
    }

    public record InvoiceBalance(UUID id, String invoiceNumber, LocalDate issueDate, InvoiceStatus status,
                              BigDecimal total, BigDecimal paid, BigDecimal balance) {
    }

    /** {@code balanceDue} is what the patient owes; {@code creditAvailable} what they have paid ahead; {@code net} the difference. */
    public record Account(UUID patientId, List<InvoiceBalance> invoices, List<ReceiptView> receipts, BigDecimal totalInvoiced,
                          BigDecimal totalPaid, BigDecimal balanceDue, BigDecimal creditAvailable, BigDecimal net) {
    }
}
