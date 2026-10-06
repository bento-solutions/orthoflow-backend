package com.orthoflow.billing.application.service;

import com.orthoflow.billing.application.dto.ReceiptDtos.Allocation;
import com.orthoflow.billing.application.dto.ReceiptDtos.ReceiptView;
import com.orthoflow.billing.application.dto.ReceiptDtos.RecordReceipt;
import com.orthoflow.billing.domain.model.*;
import com.orthoflow.billing.domain.model.PaymentPlan.Frequency;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.PaymentPlanJpaRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Instalment plans for treatment contracts. The schedule is generated once, at
 * creation, from a total, a down payment, a count and a frequency; paying an
 * instalment goes through the receipt ledger like any other money, so the plan
 * is a schedule over the account, not a second place money lives.
 */
@Service
@RequiredArgsConstructor
public class PaymentPlanService {

    @io.swagger.v3.oas.annotations.media.Schema(name = "PaymentPlanCreate")

    public record Create(UUID patientTreatmentId, UUID invoiceId, UUID practitionerId,
                         @NotNull @DecimalMin("0.01") BigDecimal total, @DecimalMin("0.00") BigDecimal downPayment,
                         @Min(1) @Max(120) int instalmentCount, @NotNull Frequency frequency, @NotNull LocalDate startDate,
                         String notes) {
    }

    public record Pay(@DecimalMin("0.01") BigDecimal amount, @NotNull PaymentMethod method, LocalDate date, String reference) {
    }

    public record InstalmentView(UUID id, int seq, LocalDate dueDate, BigDecimal amount, BigDecimal paidAmount,
                                 PaymentPlanInstalment.Status status, OffsetDateTime paidAt, boolean overdue) {
    }

    public record PlanView(UUID id, UUID patientId, UUID patientTreatmentId, UUID invoiceId, UUID practitionerId,
                           BigDecimal total, BigDecimal downPayment, int instalmentCount, Frequency frequency,
                           LocalDate startDate, PaymentPlan.Status status, String notes, BigDecimal paid, BigDecimal remaining,
                           List<InstalmentView> instalments) {
    }

    /** One line of "to do today": who owes what, since when. */
    public record DueView(UUID instalmentId, UUID planId, UUID patientId, String patientName, String patientPhone,
                          int seq, LocalDate dueDate, BigDecimal remaining, long daysOverdue) {
    }

    /** A scheduled amount before it is attached to a plan. */
    public record Slot(int seq, LocalDate dueDate, BigDecimal amount) {
    }

    private final PaymentPlanJpaRepository plans;
    private final InvoiceRepository invoices;
    private final ReceiptService receiptService;
    private final PatientLookup patientLookup;
    private final PracticeZone practiceZone;

    /**
     * The down payment (seq 0) falls due on the start date; the remainder is split
     * into equal instalments, one period apart, the last absorbing the cents that
     * do not divide evenly so the schedule always sums to the total exactly.
     */
    public static List<Slot> schedule(BigDecimal total, BigDecimal down, int count, Frequency frequency, LocalDate start) {
        BigDecimal downPayment = down == null ? BigDecimal.ZERO : down;
        if (downPayment.compareTo(total) >= 0) {
            throw new ValidationException("The down payment must be less than the total");
        }
        List<Slot> slots = new ArrayList<>();
        if (downPayment.signum() > 0) {
            slots.add(new Slot(0, start, downPayment.setScale(2, RoundingMode.HALF_UP)));
        }
        BigDecimal remainder = total.subtract(downPayment).setScale(2, RoundingMode.HALF_UP);
        BigDecimal each = remainder.divide(BigDecimal.valueOf(count), 2, RoundingMode.DOWN);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 1; i <= count; i++) {
            BigDecimal amount = i == count ? remainder.subtract(allocated) : each;
            allocated = allocated.add(amount);
            slots.add(new Slot(i, due(start, frequency, i), amount));
        }
        return slots;
    }

    private static LocalDate due(LocalDate start, Frequency frequency, int index) {
        return switch (frequency) {
            case WEEKLY -> start.plusWeeks(index);
            case BIWEEKLY -> start.plusWeeks(2L * index);
            case MONTHLY -> start.plusMonths(index);
            case QUARTERLY -> start.plusMonths(3L * index);
        };
    }

    @Transactional
    public PlanView create(UUID practiceId, UUID actorId, UUID patientId, Create r) {
        if (!patientLookup.exists(patientId)) {
            throw new NotFoundException("Patient not found");
        }
        if (r.invoiceId() != null) {
            Invoice invoice = invoices.findById(r.invoiceId()).filter(i -> practiceId.equals(i.getPracticeId()) && patientId.equals(i.getPatientId()))
                    .orElseThrow(() -> new NotFoundException("Invoice not found"));
            if (r.total().compareTo(invoice.getTotal()) > 0) {
                throw new ValidationException("The plan (" + r.total() + ") is larger than its invoice (" + invoice.getTotal() + ")");
            }
        }
        PaymentPlan plan = PaymentPlan.builder().practiceId(practiceId).patientId(patientId)
                .patientTreatmentId(r.patientTreatmentId()).invoiceId(r.invoiceId()).practitionerId(r.practitionerId())
                .total(r.total()).downPayment(r.downPayment() == null ? BigDecimal.ZERO : r.downPayment())
                .instalmentCount(r.instalmentCount()).frequency(r.frequency()).startDate(r.startDate())
                .notes(r.notes()).createdBy(actorId).build();
        for (Slot slot : schedule(r.total(), r.downPayment(), r.instalmentCount(), r.frequency(), r.startDate())) {
            plan.getInstalments().add(PaymentPlanInstalment.builder().plan(plan).seq(slot.seq())
                    .dueDate(slot.dueDate()).amount(slot.amount()).build());
        }
        return view(plans.save(plan), today(practiceId));
    }

    @Transactional(readOnly = true)
    public List<PlanView> forPatient(UUID practiceId, UUID patientId) {
        LocalDate today = today(practiceId);
        return plans.findByPatientIdAndPracticeIdOrderByCreatedAtDesc(patientId, practiceId).stream().map(p -> view(p, today)).toList();
    }

    @Transactional(readOnly = true)
    public PlanView get(UUID practiceId, UUID id) {
        return view(require(practiceId, id), today(practiceId));
    }

    @Transactional
    public PlanView cancel(UUID practiceId, UUID id) {
        PaymentPlan plan = require(practiceId, id);
        if (plan.getStatus() != PaymentPlan.Status.ACTIVE) {
            throw new ConflictException("Only an active plan can be cancelled");
        }
        plan.setStatus(PaymentPlan.Status.CANCELLED);
        plan.getInstalments().stream().filter(i -> i.getStatus() == PaymentPlanInstalment.Status.PENDING)
                .forEach(i -> i.setStatus(PaymentPlanInstalment.Status.CANCELLED));
        return view(plan, today(practiceId));
    }

    /**
     * Takes a payment against an instalment. The money is recorded as a receipt
     * (and applied to the plan's invoice when it has one, up to what is owed on
     * it), then the instalment is marked off.
     */
    @Transactional
    public PlanView pay(UUID practiceId, UUID actorId, UUID instalmentId, Pay r) {
        PaymentPlanInstalment instalment = plans.findInstalment(instalmentId, practiceId)
                .orElseThrow(() -> new NotFoundException("Instalment not found"));
        PaymentPlan plan = instalment.getPlan();
        if (plan.getStatus() != PaymentPlan.Status.ACTIVE || instalment.getStatus() != PaymentPlanInstalment.Status.PENDING) {
            throw new ConflictException("This instalment cannot be paid (it is " + instalment.getStatus() + ")");
        }
        BigDecimal amount = r.amount() == null ? instalment.remaining() : r.amount();
        if (amount.compareTo(instalment.remaining()) > 0) {
            throw new ValidationException("The instalment has only " + instalment.remaining() + " left to pay");
        }
        List<Allocation> allocations = new ArrayList<>();
        if (plan.getInvoiceId() != null) {
            invoices.findById(plan.getInvoiceId()).ifPresent(invoice -> {
                BigDecimal paid = invoice.getPayments().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal outstanding = invoice.getTotal().subtract(paid);
                BigDecimal apply = amount.min(outstanding);
                if (apply.signum() > 0 && invoice.getStatus() != InvoiceStatus.CANCELLED) {
                    allocations.add(new Allocation(invoice.getId(), apply));
                }
            });
        }
        ReceiptView receipt = receiptService.record(practiceId, actorId, plan.getPatientId(),
                new RecordReceipt(amount, r.method(), r.date(), plan.getPractitionerId(), r.reference(),
                        "Échéance " + (instalment.getSeq() == 0 ? "acompte" : "n° " + instalment.getSeq()), allocations, false, null));
        instalment.setPaidAmount(instalment.getPaidAmount().add(amount));
        instalment.setReceiptId(receipt.id());
        if (instalment.remaining().signum() == 0) {
            instalment.setStatus(PaymentPlanInstalment.Status.PAID);
            instalment.setPaidAt(OffsetDateTime.now());
        }
        if (plan.getInstalments().stream().noneMatch(i -> i.getStatus() == PaymentPlanInstalment.Status.PENDING)) {
            plan.setStatus(PaymentPlan.Status.COMPLETED);
        }
        return view(plan, today(practiceId));
    }

    @Transactional(readOnly = true)
    public List<DueView> due(UUID practiceId, LocalDate until) {
        LocalDate today = today(practiceId);
        List<PaymentPlanInstalment> rows = plans.due(practiceId, until == null ? today : until);
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(rows.stream().map(i -> i.getPlan().getPatientId()).distinct().toList());
        return rows.stream().map(i -> {
            PatientSummary p = patients.get(i.getPlan().getPatientId());
            return new DueView(i.getId(), i.getPlan().getId(), i.getPlan().getPatientId(), p == null ? null : p.fullName(),
                    p == null ? null : p.phone(), i.getSeq(), i.getDueDate(), i.remaining(),
                    Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(i.getDueDate(), today)));
        }).collect(Collectors.toList());
    }

    private PaymentPlan require(UUID practiceId, UUID id) {
        return plans.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Payment plan not found"));
    }

    private LocalDate today(UUID practiceId) {
        return LocalDate.now(practiceZone.of(practiceId));
    }

    private PlanView view(PaymentPlan p, LocalDate today) {
        List<InstalmentView> parts = p.getInstalments().stream().map(i -> new InstalmentView(i.getId(), i.getSeq(), i.getDueDate(),
                i.getAmount(), i.getPaidAmount(), i.getStatus(), i.getPaidAt(),
                i.getStatus() == PaymentPlanInstalment.Status.PENDING && i.getDueDate().isBefore(today))).toList();
        BigDecimal paid = p.getInstalments().stream().map(PaymentPlanInstalment::getPaidAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PlanView(p.getId(), p.getPatientId(), p.getPatientTreatmentId(), p.getInvoiceId(), p.getPractitionerId(),
                p.getTotal(), p.getDownPayment(), p.getInstalmentCount(), p.getFrequency(), p.getStartDate(), p.getStatus(),
                p.getNotes(), paid, p.getTotal().subtract(paid), parts);
    }
}
