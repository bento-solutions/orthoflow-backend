package com.orthoflow.billing.application.service;

import com.orthoflow.billing.application.dto.ReceiptDtos.*;
import com.orthoflow.billing.domain.model.*;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.ChequeJpaRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.PaymentJpaRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.ReceiptJpaRepository;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.team.application.service.PractitionerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The patient account. Money arrives as a receipt; allocating it to invoices is
 * a separate step, so an advance, a payment spread over two invoices and a
 * credit used later all fall out of the same model. Every allocation goes
 * through {@link BillingService#allocate}, the one place the "never overpay,
 * never pay a cancelled invoice" rules live.
 *
 * <p>Invoices are locked in id order before anything is changed, so two people
 * paying overlapping invoices at once wait for each other instead of deadlocking.
 */
@Service
@RequiredArgsConstructor
public class ReceiptService {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final ReceiptJpaRepository receipts;
    private final PaymentJpaRepository payments;
    private final ChequeJpaRepository cheques;
    private final InvoiceRepository invoices;
    private final BillingService billingService;
    private final PatientLookup patientLookup;
    private final PractitionerService practitionerService;
    private final PracticeZone practiceZone;
    private final LiveEventPublisher liveEvents;

    @Transactional
    public ReceiptView record(UUID practiceId, UUID actorId, UUID patientId, RecordReceipt r) {
        if (!patientLookup.exists(patientId)) {
            throw new NotFoundException("Patient not found");
        }
        if (r.practitionerId() != null) {
            practitionerService.require(practiceId, r.practitionerId());
        }
        if (r.cheque() != null && r.method() != PaymentMethod.CHEQUE) {
            throw new ValidationException("Cheque details only go with a cheque payment");
        }
        LocalDate date = r.receiptDate() != null ? r.receiptDate()
                : LocalDate.now(practiceZone.of(practiceId));
        List<Allocation> wanted = r.allocations() == null ? List.of() : r.allocations();
        if (wanted.stream().map(Allocation::invoiceId).distinct().count() != wanted.size()) {
            throw new ValidationException("An invoice can appear only once in the allocations");
        }
        BigDecimal explicit = wanted.stream().map(Allocation::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (explicit.compareTo(r.amount()) > 0) {
            throw new ValidationException("The allocations (" + explicit + ") exceed the amount received (" + r.amount() + ")");
        }

        Receipt receipt = Receipt.builder().id(UUID.randomUUID()).practiceId(practiceId).patientId(patientId)
                .amount(r.amount()).method(r.method()).receiptDate(date).practitionerId(r.practitionerId())
                .reference(r.reference()).notes(r.notes()).recordedBy(actorId).build();

        // The two rows point at each other (receipt.cheque_id, cheque.receipt_id), so the
        // receipt goes in first, then the cheque, and the receipt is told about it.
        receipts.save(receipt);
        if (r.cheque() != null) {
            Cheque cheque = cheques.save(Cheque.builder().id(UUID.randomUUID()).practiceId(practiceId)
                    .number(r.cheque().number()).bank(r.cheque().bank()).drawerName(r.cheque().drawerName())
                    .patientId(patientId).amount(r.amount()).dueDate(r.cheque().dueDate())
                    .receiptId(receipt.getId()).createdBy(actorId).build());
            receipt.setChequeId(cheque.getId());
        }

        Map<UUID, Invoice> locked = new LinkedHashMap<>();
        for (UUID id : wanted.stream().map(Allocation::invoiceId).sorted().toList()) {
            locked.put(id, lockOwned(practiceId, patientId, id));
        }
        BigDecimal remaining = r.amount();
        for (Allocation a : wanted) {
            billingService.allocate(locked.get(a.invoiceId()), a.amount(), receipt, date, actorId);
            remaining = remaining.subtract(a.amount());
        }
        if (Boolean.TRUE.equals(r.autoAllocate()) && remaining.signum() > 0) {
            for (Invoice open : invoices.findOpenByPatientForUpdate(patientId)) {
                if (remaining.signum() <= 0) {
                    break;
                }
                if (locked.containsKey(open.getId()) || !open.getPracticeId().equals(practiceId)) {
                    continue;
                }
                BigDecimal outstanding = open.getTotal().subtract(paidOn(open));
                BigDecimal take = remaining.min(outstanding);
                if (take.signum() > 0) {
                    billingService.allocate(open, take, receipt, date, actorId);
                    locked.put(open.getId(), open);
                    remaining = remaining.subtract(take);
                }
            }
        }
        locked.values().forEach(invoices::save);
        liveEvents.publish(practiceId, "finance", receipt.getId());
        return view(receipt, invoicesById(locked.values()));
    }

    /** Spends a patient's credit on an invoice, oldest receipt first. */
    @Transactional
    public List<AllocationView> applyCredit(UUID practiceId, UUID actorId, UUID patientId, ApplyCredit r) {
        Invoice invoice = lockOwned(practiceId, patientId, r.invoiceId());
        BigDecimal outstanding = invoice.getTotal().subtract(paidOn(invoice));
        BigDecimal wanted = r.amount() == null ? outstanding : r.amount();
        if (wanted.compareTo(outstanding) > 0) {
            throw new ConflictException("Payment of " + wanted + " exceeds the outstanding balance of " + outstanding);
        }
        LocalDate today = LocalDate.now(practiceZone.of(practiceId));
        List<AllocationView> made = new ArrayList<>();
        BigDecimal left = wanted;
        for (Receipt receipt : receipts.withCreditForUpdate(patientId)) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal available = receipt.getAmount().subtract(receipts.allocatedAmount(receipt.getId()));
            BigDecimal take = left.min(available);
            Payment p = billingService.allocate(invoice, take, receipt, today, actorId);
            made.add(new AllocationView(p.getId(), invoice.getId(), invoice.getInvoiceNumber(), take, today));
            left = left.subtract(take);
        }
        if (left.signum() > 0) {
            throw new ConflictException("The patient's credit is " + wanted.subtract(left) + ", less than the " + wanted + " asked for");
        }
        invoices.save(invoice);
        liveEvents.publish(practiceId, "finance", invoice.getId());
        return made;
    }

    /**
     * Voids a receipt: its allocations are taken back (each invoice's status
     * follows) and the record stays, marked with who and why. A receipt backed by
     * a cheque is voided only through the cheque register, so the two cannot
     * disagree.
     */
    @Transactional
    public ReceiptView voidReceipt(UUID practiceId, UUID actorId, UUID receiptId, String reason, boolean viaCheque) {
        Receipt receipt = receipts.findByIdForUpdate(receiptId).filter(x -> x.getPracticeId().equals(practiceId))
                .orElseThrow(() -> new NotFoundException("Receipt not found"));
        if (receipt.isVoided()) {
            throw new ConflictException("This receipt is already voided");
        }
        if (receipt.getChequeId() != null && !viaCheque) {
            throw new ConflictException("This receipt is a cheque: reject the cheque in the cheque register to reverse it");
        }
        List<Payment> allocations = payments.findByReceiptId(receiptId);
        Map<UUID, List<Payment>> byInvoice = allocations.stream().collect(Collectors.groupingBy(p -> p.getInvoice().getId()));
        for (UUID invoiceId : byInvoice.keySet().stream().sorted().toList()) {
            Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow();
            for (Payment p : byInvoice.get(invoiceId)) {
                // Same instance inside one persistence context; matched by id otherwise.
                Payment attached = invoice.getPayments().stream()
                        .filter(x -> x == p || (x.getId() != null && x.getId().equals(p.getId()))).findFirst().orElse(p);
                billingService.deallocate(invoice, attached, actorId, reason);
            }
            invoices.save(invoice);
        }
        receipt.setVoidedAt(OffsetDateTime.now());
        receipt.setVoidedBy(actorId);
        receipt.setVoidReason(reason);
        liveEvents.publish(practiceId, "finance", receiptId);
        return view(receipt, Map.of());
    }

    @Transactional(readOnly = true)
    public Account account(UUID practiceId, UUID patientId) {
        if (!patientLookup.exists(patientId)) {
            throw new NotFoundException("Patient not found");
        }
        List<Invoice> all = invoices.findByPatientId(patientId).stream()
                .filter(i -> i.getPracticeId().equals(practiceId))
                .sorted(Comparator.comparing(Invoice::getIssueDate, Comparator.nullsLast(Comparator.naturalOrder())).reversed())
                .toList();
        Map<UUID, Invoice> byId = all.stream().collect(Collectors.toMap(Invoice::getId, Function.identity()));
        List<InvoiceBalance> lines = all.stream().map(i -> {
            BigDecimal paid = paidOn(i);
            return new InvoiceBalance(i.getId(), i.getInvoiceNumber(), i.getIssueDate(), i.getStatus(), i.getTotal(), paid,
                    i.getStatus() == InvoiceStatus.CANCELLED ? ZERO : i.getTotal().subtract(paid));
        }).toList();
        List<Receipt> rows = receipts.findByPatientIdOrderByReceiptDateDescCreatedAtDesc(patientId).stream()
                .filter(x -> x.getPracticeId().equals(practiceId)).toList();
        List<ReceiptView> views = rows.stream().map(x -> view(x, byId)).toList();

        BigDecimal invoiced = all.stream().filter(i -> i.getStatus() != InvoiceStatus.CANCELLED)
                .map(Invoice::getTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal paid = all.stream().filter(i -> i.getStatus() != InvoiceStatus.CANCELLED)
                .map(this::paidOn).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = views.stream().filter(v -> !v.voided()).map(ReceiptView::unallocated).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal balance = invoiced.subtract(paid);
        return new Account(patientId, lines, views, invoiced, paid, balance, credit, balance.subtract(credit));
    }

    private ReceiptView view(Receipt r, Map<UUID, Invoice> invoicesById) {
        List<Payment> allocations = payments.findByReceiptId(r.getId());
        List<AllocationView> parts = allocations.stream().map(p -> {
            UUID invoiceId = p.getInvoice().getId();
            Invoice inv = invoicesById.get(invoiceId);
            return new AllocationView(p.getId(), invoiceId, inv == null ? null : inv.getInvoiceNumber(), p.getAmount(), p.getPaymentDate());
        }).toList();
        BigDecimal allocated = parts.stream().map(AllocationView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ReceiptView(r.getId(), r.getPatientId(), r.getAmount(), allocated,
                r.isVoided() ? ZERO : r.getAmount().subtract(allocated), r.getMethod(), r.getReceiptDate(), r.getPractitionerId(),
                r.getReference(), r.getNotes(), r.isVoided(), r.getVoidedAt(), r.getVoidReason(), r.getChequeId(), parts, r.getCreatedAt());
    }

    private Map<UUID, Invoice> invoicesById(Collection<Invoice> list) {
        return list.stream().collect(Collectors.toMap(Invoice::getId, Function.identity(), (a, b) -> a));
    }

    private Invoice lockOwned(UUID practiceId, UUID patientId, UUID invoiceId) {
        Invoice invoice = invoices.findByIdForUpdate(invoiceId).orElseThrow(() -> new NotFoundException("Invoice not found"));
        if (!invoice.getPracticeId().equals(practiceId) || !patientId.equals(invoice.getPatientId())) {
            // Another patient's invoice is "not found" to this account, not a different error.
            throw new NotFoundException("Invoice not found");
        }
        return invoice;
    }

    private BigDecimal paidOn(Invoice invoice) {
        return invoice.getPayments().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
