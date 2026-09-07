package com.orthoflow.billing.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.billing.application.dto.*;
import com.orthoflow.billing.domain.model.*;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.billing.domain.repository.PaymentRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.InvoiceAuditLogJpaRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final int MONEY_SCALE = 2;

    private final InvoiceRepository invoiceRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceNumberGenerator invoiceNumberGenerator;
    private final InvoiceAuditLogJpaRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    /**
     * Writes to billing_audit_log, which existed since the first migration
     * but had no writer (audit II.3) — every invoice mutation was
     * untraceable. Audit writes never fail the business transaction they
     * describe: a JSON-serialization hiccup on the "details" blob shouldn't
     * block a payment from being recorded.
     */
    private void audit(UUID invoiceId, String action, UUID actorId, Map<String, Object> details) {
        String json;
        try {
            json = objectMapper.writeValueAsString(details);
        } catch (Exception e) {
            log.warn("Could not serialize audit details for invoice {} action {}", invoiceId, action, e);
            json = null;
        }
        auditLogRepository.save(InvoiceAuditLog.builder()
                .invoiceId(invoiceId)
                .action(action)
                .actorId(actorId)
                .details(json)
                .build());
    }

    @Transactional
    public InvoiceResponse createInvoice(CreateInvoiceRequest request, UUID creatorId) {
        String invoiceNumber = invoiceNumberGenerator.generate(request.getRegionCode());

        Invoice invoice = Invoice.builder()
                .practiceId(request.getPracticeId())
                .patientId(request.getPatientId())
                .treatmentPlanId(request.getTreatmentPlanId())
                .invoiceNumber(invoiceNumber)
                .status(InvoiceStatus.DRAFT)
                .issueDate(LocalDate.now())
                .dueDate(LocalDate.now().plusDays(14))
                .currency(request.getCurrency())
                .regionCode(request.getRegionCode())
                .notes(request.getNotes())
                .createdBy(creatorId)
                .build();

        // subtotal is the pre-discount sum of every line; discountAmount is
        // tracked separately so the figures the UI showed the user (subtotal,
        // discount, total) are the figures actually persisted — previously
        // the discount was baked into "subtotal" and discountAmount was
        // never set at all (audit III.8).
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountAmount = BigDecimal.ZERO;
        for (InvoiceLineRequest lineReq : request.getLines()) {
            BigDecimal grossLineTotal = lineReq.getUnitPrice().multiply(lineReq.getQuantity());
            BigDecimal lineDiscount = grossLineTotal
                    .multiply(lineReq.getDiscountPct().divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP));
            BigDecimal netLineTotal = round(grossLineTotal.subtract(lineDiscount));

            InvoiceLine line = InvoiceLine.builder()
                    .actCode(lineReq.getActCode())
                    .label(lineReq.getLabel())
                    .quantity(lineReq.getQuantity())
                    .unitPrice(lineReq.getUnitPrice())
                    .discountPct(lineReq.getDiscountPct())
                    .lineTotal(netLineTotal)
                    .sortOrder(lineReq.getSortOrder())
                    .build();

            invoice.addLine(line);
            subtotal = subtotal.add(grossLineTotal);
            discountAmount = discountAmount.add(lineDiscount);
        }

        subtotal = round(subtotal);
        discountAmount = round(discountAmount);
        BigDecimal taxableAmount = subtotal.subtract(discountAmount);
        BigDecimal taxRate = getTaxRate(request.getRegionCode());
        BigDecimal taxAmount = round(taxableAmount.multiply(taxRate));

        invoice.setSubtotal(subtotal);
        invoice.setDiscountAmount(discountAmount);
        invoice.setTaxAmount(taxAmount);
        invoice.setTotal(taxableAmount.add(taxAmount));

        Invoice saved = invoiceRepository.save(invoice);
        audit(saved.getId(), "CREATED", creatorId, Map.of(
                "invoiceNumber", invoiceNumber,
                "total", saved.getTotal().toString()));
        return mapToResponse(saved);
    }

    @Transactional
    public void recordPayment(UUID invoiceId, RecordPaymentRequest request, UUID recorderId) {
        // Row-locked: two concurrent payments must not both read the same
        // outstanding balance and each pass the check below (audit M2).
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            throw new ConflictException("Cannot record a payment against a cancelled invoice");
        }
        if (invoice.getStatus() == InvoiceStatus.PAID) {
            throw new ConflictException("This invoice is already fully paid");
        }

        BigDecimal alreadyPaid = totalPaid(invoice);
        BigDecimal outstanding = invoice.getTotal().subtract(alreadyPaid);
        if (request.getAmount().compareTo(outstanding) > 0) {
            throw new ConflictException(
                    "Payment of " + request.getAmount() + " exceeds the outstanding balance of " + outstanding);
        }

        Payment payment = Payment.builder()
                .invoice(invoice)
                .amount(request.getAmount())
                .method(request.getMethod())
                .paymentDate(request.getPaymentDate())
                .reference(request.getReference())
                .notes(request.getNotes())
                .recordedBy(recorderId)
                .build();

        invoice.addPayment(payment);

        BigDecimal totalPaidAfter = alreadyPaid.add(request.getAmount());
        if (totalPaidAfter.compareTo(invoice.getTotal()) >= 0) {
            invoice.setStatus(InvoiceStatus.PAID);
        } else {
            invoice.setStatus(InvoiceStatus.PARTIALLY_PAID);
        }

        invoiceRepository.save(invoice);
        audit(invoice.getId(), "PAYMENT_RECORDED", recorderId, Map.of(
                "amount", request.getAmount().toString(),
                "method", String.valueOf(request.getMethod()),
                "resultingStatus", invoice.getStatus().toString()));
    }

    /**
     * Voids an invoice that hasn't been paid against. There is deliberately
     * no path from PAID/PARTIALLY_PAID to CANCELLED here — reversing money
     * that has already changed hands is a refund, a different operation
     * this codebase doesn't model yet (audit II.14). Added for
     * TreatmentInvoiceService#cancelInvoice (ADR 0005 / P3 #39) to void the
     * linked invoice when a treatment session is cancelled before it's
     * been paid; not yet exposed as its own REST endpoint for a
     * user-initiated "void this invoice" action outside that flow.
     */
    @Transactional
    public void cancelInvoice(UUID invoiceId, UUID actorId) {
        Invoice invoice = invoiceRepository.findByIdForUpdate(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));

        if (invoice.getStatus() == InvoiceStatus.CANCELLED) {
            return;
        }
        if (invoice.getStatus() == InvoiceStatus.PAID || invoice.getStatus() == InvoiceStatus.PARTIALLY_PAID) {
            throw new ConflictException(
                    "Cannot cancel an invoice with payments recorded against it; a refund is a separate, unmodeled operation");
        }

        invoice.setStatus(InvoiceStatus.CANCELLED);
        invoiceRepository.save(invoice);
        audit(invoice.getId(), "CANCELLED", actorId, Map.of());
    }

    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<InvoiceResponse> getInvoices(
            UUID patientId, org.springframework.data.domain.Pageable pageable) {
        org.springframework.data.domain.Page<Invoice> invoices = patientId != null
                ? invoiceRepository.findByPatientId(patientId, pageable)
                : invoiceRepository.findAll(pageable);
        return invoices.map(this::mapToResponse);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(UUID id) {
        return invoiceRepository.findById(id)
                .map(this::mapToResponse)
                .orElseThrow(() -> new NotFoundException("Invoice not found"));
    }

    @Transactional(readOnly = true)
    public BillingSummaryResponse getBillingSummary() {
        LocalDate periodStart = LocalDate.now().withDayOfMonth(1);
        LocalDate periodEnd = LocalDate.now().withDayOfMonth(LocalDate.now().lengthOfMonth());

        // Aggregates in the database, not findAll() + an in-memory reduce
        // over every invoice (plus its lines and payments, N+1) on every
        // dashboard load (audit M1/II.12).
        BigDecimal totalInvoiced = round(invoiceRepository.sumTotalIssuedBetween(periodStart, periodEnd));
        BigDecimal totalCollected =
                round(invoiceRepository.sumPaymentsForInvoicesIssuedBetween(periodStart, periodEnd));

        return BillingSummaryResponse.builder()
                .periodStart(periodStart)
                .periodEnd(periodEnd)
                .totalInvoiced(totalInvoiced)
                .totalCollected(totalCollected)
                .outstandingAmount(totalInvoiced.subtract(totalCollected))
                .invoiceCount((int) invoiceRepository.count())
                .byStatus(invoiceRepository.countByStatus())
                .build();
    }

    private BigDecimal totalPaid(Invoice invoice) {
        return invoice.getPayments().stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal round(BigDecimal value) {
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal getTaxRate(String regionCode) {
        if (regionCode == null || regionCode.isBlank()) {
            return BigDecimal.ZERO;
        }
        return switch (regionCode.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FR" -> BigDecimal.valueOf(0.20);
            case "MA" -> BigDecimal.ZERO;
            case "US" -> BigDecimal.valueOf(0.08);
            default -> BigDecimal.ZERO;
        };
    }

    private InvoiceResponse mapToResponse(Invoice invoice) {
        BigDecimal amountPaid = round(totalPaid(invoice));
        BigDecimal balanceDue = invoice.getTotal() != null ? invoice.getTotal().subtract(amountPaid) : null;

        return InvoiceResponse.builder()
                .id(invoice.getId())
                .practiceId(invoice.getPracticeId())
                .patientId(invoice.getPatientId())
                .invoiceNumber(invoice.getInvoiceNumber())
                .status(invoice.getStatus())
                .issueDate(invoice.getIssueDate())
                .dueDate(invoice.getDueDate())
                .currency(invoice.getCurrency())
                .subtotal(invoice.getSubtotal())
                .discountAmount(invoice.getDiscountAmount())
                .taxAmount(invoice.getTaxAmount())
                .total(invoice.getTotal())
                .amountPaid(amountPaid)
                .balanceDue(balanceDue)
                .regionCode(invoice.getRegionCode())
                .createdAt(invoice.getCreatedAt())
                .lines(invoice.getLines().stream().map(this::mapLineToResponse).collect(Collectors.toList()))
                .payments(invoice.getPayments().stream().map(this::mapPaymentToResponse).collect(Collectors.toList()))
                .build();
    }

    private InvoiceLineResponse mapLineToResponse(InvoiceLine line) {
        return InvoiceLineResponse.builder()
                .id(line.getId())
                .actCode(line.getActCode())
                .label(line.getLabel())
                .quantity(line.getQuantity())
                .unitPrice(line.getUnitPrice())
                .lineTotal(line.getLineTotal())
                .build();
    }

    private PaymentResponse mapPaymentToResponse(Payment payment) {
        return PaymentResponse.builder()
                .id(payment.getId())
                .amount(payment.getAmount())
                .method(payment.getMethod())
                .paymentDate(payment.getPaymentDate())
                .reference(payment.getReference())
                .build();
    }
}
