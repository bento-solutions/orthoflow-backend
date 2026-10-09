package com.orthoflow.billing.application.service;

import com.orthoflow.billing.application.dto.ReceiptDtos.*;
import com.orthoflow.billing.domain.model.*;
import com.orthoflow.billing.domain.repository.InvoiceRepository;
import com.orthoflow.billing.infrastructure.adapter.persistence.*;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.team.application.service.PractitionerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The patient account: money arrives as a receipt and is allocated to invoices in
 * a separate step, so an advance, a payment spanning two invoices and credit spent
 * later all come out of one model — under the same never-overpay rule as before.
 */
class ReceiptServiceTest {

    private final UUID practice = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    private ReceiptJpaRepository receipts;
    private PaymentJpaRepository payments;
    private ChequeJpaRepository cheques;
    private InvoiceRepository invoices;
    private ReceiptService service;
    private final Map<UUID, Invoice> store = new LinkedHashMap<>();
    private final Map<UUID, Receipt> receiptStore = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        receipts = mock(ReceiptJpaRepository.class);
        payments = mock(PaymentJpaRepository.class);
        cheques = mock(ChequeJpaRepository.class);
        invoices = mock(InvoiceRepository.class);
        PatientLookup patients = mock(PatientLookup.class);
        when(patients.exists(patient)).thenReturn(true);
        when(receipts.save(any(Receipt.class))).thenAnswer(inv -> {
            Receipt r = inv.getArgument(0);
            receiptStore.put(r.getId(), r);
            return r;
        });
        when(receipts.findByIdForUpdate(any())).thenAnswer(inv -> Optional.ofNullable(receiptStore.get(inv.<UUID>getArgument(0))));
        when(receipts.allocatedAmount(any())).thenAnswer(inv -> allocated(inv.getArgument(0)));
        when(receipts.withCreditForUpdate(patient)).thenAnswer(inv -> receiptStore.values().stream()
                .filter(r -> !r.isVoided() && r.getAmount().compareTo(allocated(r.getId())) > 0).toList());
        when(payments.findByReceiptId(any())).thenAnswer(inv -> allPayments().stream()
                .filter(p -> inv.<UUID>getArgument(0).equals(p.getReceiptId())).toList());
        when(receipts.findByPatientIdOrderByReceiptDateDescCreatedAtDesc(patient))
                .thenAnswer(inv -> new ArrayList<>(receiptStore.values()));
        when(invoices.findByIdForUpdate(any())).thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));
        when(invoices.findOpenByPatientForUpdate(patient)).thenAnswer(inv -> store.values().stream()
                .filter(i -> i.getStatus() != InvoiceStatus.PAID && i.getStatus() != InvoiceStatus.CANCELLED)
                .sorted(Comparator.comparing(Invoice::getIssueDate)).toList());
        when(invoices.findByPatientId(patient)).thenAnswer(inv -> new ArrayList<>(store.values()));
        when(invoices.save(any(Invoice.class))).thenAnswer(inv -> inv.getArgument(0));
        when(cheques.save(any(Cheque.class))).thenAnswer(inv -> inv.getArgument(0));

        BillingService billing = new BillingService(invoices, mock(com.orthoflow.billing.domain.repository.PaymentRepository.class),
                mock(InvoiceNumberGenerator.class), mock(InvoiceAuditLogJpaRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(), receipts, mock(PractitionerService.class),
                mock(com.orthoflow.patient.application.port.PatientLookup.class), id -> { }, com.orthoflow.testsupport.Tenants.fixed(UUID.randomUUID()));
        service = new ReceiptService(receipts, payments, cheques, invoices, billing, patients,
                mock(PractitionerService.class), id -> ZoneId.of("Africa/Casablanca"), mock(LiveEventPublisher.class));
    }

    private List<Payment> allPayments() {
        return store.values().stream().flatMap(i -> i.getPayments().stream()).toList();
    }

    private BigDecimal allocated(UUID receiptId) {
        return allPayments().stream().filter(p -> receiptId.equals(p.getReceiptId())).map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Invoice invoice(String total, LocalDate issued) {
        Invoice i = Invoice.builder().id(UUID.randomUUID()).practiceId(practice).patientId(patient).invoiceNumber("INV-" + store.size())
                .status(InvoiceStatus.DRAFT).total(new BigDecimal(total)).issueDate(issued).payments(new ArrayList<>()).build();
        store.put(i.getId(), i);
        return i;
    }

    private RecordReceipt receipt(String amount, boolean auto, Allocation... allocations) {
        return new RecordReceipt(new BigDecimal(amount), PaymentMethod.CASH, LocalDate.of(2026, 10, 5), null, null, null,
                List.of(allocations), auto, null);
    }

    @Test
    void aReceiptWithNoAllocationIsAnAdvanceHeldAsCredit() {
        ReceiptView view = service.record(practice, actor, patient, receipt("500", false));

        assertThat(view.allocated()).isEqualByComparingTo("0");
        assertThat(view.unallocated()).isEqualByComparingTo("500");
    }

    @Test
    void oneReceiptCanPayTwoInvoices() {
        Invoice a = invoice("300", LocalDate.of(2026, 9, 1));
        Invoice b = invoice("400", LocalDate.of(2026, 9, 2));

        ReceiptView view = service.record(practice, actor, patient, receipt("700", false,
                new Allocation(a.getId(), new BigDecimal("300")), new Allocation(b.getId(), new BigDecimal("400"))));

        assertThat(a.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(b.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(view.allocations()).hasSize(2);
        assertThat(view.unallocated()).isEqualByComparingTo("0");
    }

    @Test
    void allocationsCannotExceedTheAmountReceived() {
        Invoice a = invoice("300", LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> service.record(practice, actor, patient, receipt("200", false, new Allocation(a.getId(), new BigDecimal("300")))))
                .isInstanceOf(ValidationException.class).hasMessageContaining("exceed");
        assertThat(a.getPayments()).isEmpty();
    }

    @Test
    void anInvoiceCannotBeOverpaidAndNothingIsAllocatedWhenItFails() {
        Invoice a = invoice("100", LocalDate.of(2026, 9, 1));

        assertThatThrownBy(() -> service.record(practice, actor, patient, receipt("500", false, new Allocation(a.getId(), new BigDecimal("150")))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("exceeds the outstanding balance");
        assertThat(a.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    void autoAllocationPaysTheOldestInvoiceFirstAndKeepsTheRestAsCredit() {
        Invoice older = invoice("300", LocalDate.of(2026, 8, 1));
        Invoice newer = invoice("400", LocalDate.of(2026, 9, 1));

        ReceiptView view = service.record(practice, actor, patient, receipt("1000", true));

        assertThat(older.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(newer.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(view.allocated()).isEqualByComparingTo("700");
        assertThat(view.unallocated()).isEqualByComparingTo("300");
    }

    @Test
    void aPartialAutoAllocationLeavesTheNewerInvoicePartiallyPaid() {
        Invoice older = invoice("300", LocalDate.of(2026, 8, 1));
        Invoice newer = invoice("400", LocalDate.of(2026, 9, 1));

        service.record(practice, actor, patient, receipt("500", true));

        assertThat(older.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(newer.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    void anotherPatientsInvoiceIsNotFoundToThisAccount() {
        Invoice foreign = Invoice.builder().id(UUID.randomUUID()).practiceId(practice).patientId(UUID.randomUUID()).status(InvoiceStatus.DRAFT)
                .total(new BigDecimal("100")).payments(new ArrayList<>()).build();
        store.put(foreign.getId(), foreign);

        assertThatThrownBy(() -> service.record(practice, actor, patient, receipt("100", false, new Allocation(foreign.getId(), new BigDecimal("100")))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void creditPaysAnInvoiceLaterOldestReceiptFirst() {
        service.record(practice, actor, patient, receipt("200", false));
        service.record(practice, actor, patient, receipt("300", false));
        Invoice a = invoice("350", LocalDate.of(2026, 10, 1));

        List<AllocationView> made = service.applyCredit(practice, actor, patient, new ApplyCredit(a.getId(), null));

        assertThat(made).hasSize(2);
        assertThat(made.get(0).amount()).isEqualByComparingTo("200");
        assertThat(made.get(1).amount()).isEqualByComparingTo("150");
        assertThat(a.getStatus()).isEqualTo(InvoiceStatus.PAID);
        assertThat(service.account(practice, patient).creditAvailable()).isEqualByComparingTo("150");
    }

    @Test
    void creditCannotBeSpentBeyondWhatThePatientHas() {
        service.record(practice, actor, patient, receipt("100", false));
        Invoice a = invoice("500", LocalDate.of(2026, 10, 1));

        assertThatThrownBy(() -> service.applyCredit(practice, actor, patient, new ApplyCredit(a.getId(), new BigDecimal("400"))))
                .isInstanceOf(ConflictException.class).hasMessageContaining("credit");
    }

    @Test
    void voidingAReceiptTakesBackItsAllocationsAndTheInvoiceIsOwedAgain() {
        Invoice a = invoice("300", LocalDate.of(2026, 9, 1));
        ReceiptView paid = service.record(practice, actor, patient, receipt("300", false, new Allocation(a.getId(), new BigDecimal("300"))));
        assertThat(a.getStatus()).isEqualTo(InvoiceStatus.PAID);

        ReceiptView voided = service.voidReceipt(practice, actor, paid.id(), "saisie en double", false);

        assertThat(voided.voided()).isTrue();
        assertThat(voided.voidReason()).isEqualTo("saisie en double");
        assertThat(a.getPayments()).isEmpty();
        assertThat(a.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
        assertThat(service.account(practice, patient).balanceDue()).isEqualByComparingTo("300");
        assertThatThrownBy(() -> service.voidReceipt(practice, actor, paid.id(), "again", false)).isInstanceOf(ConflictException.class);
    }

    @Test
    void aPartiallyPaidInvoiceWhoseOnlyReceiptIsVoidedGoesBackToUnpaid() {
        Invoice a = invoice("300", LocalDate.of(2026, 9, 1));
        ReceiptView first = service.record(practice, actor, patient, receipt("100", false, new Allocation(a.getId(), new BigDecimal("100"))));
        service.record(practice, actor, patient, receipt("50", false, new Allocation(a.getId(), new BigDecimal("50"))));
        assertThat(a.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);

        service.voidReceipt(practice, actor, first.id(), "erreur", false);

        assertThat(a.getStatus()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
        assertThat(a.getPayments()).hasSize(1);
        assertThat(a.getPayments().get(0).getAmount()).isEqualByComparingTo("50");
    }

    @Test
    void aChequeReceiptIsVoidedOnlyThroughTheChequeRegister() {
        Invoice a = invoice("300", LocalDate.of(2026, 9, 1));
        ReceiptView view = service.record(practice, actor, patient, new RecordReceipt(new BigDecimal("300"), PaymentMethod.CHEQUE,
                LocalDate.of(2026, 10, 5), null, "CH1", null, List.of(new Allocation(a.getId(), new BigDecimal("300"))), false,
                new ChequeInfo("0012345", "CIH", "Sara Benziane", LocalDate.of(2026, 11, 5))));
        assertThat(view.chequeId()).isNotNull();

        assertThatThrownBy(() -> service.voidReceipt(practice, actor, view.id(), "x", false))
                .isInstanceOf(ConflictException.class).hasMessageContaining("cheque");
        service.voidReceipt(practice, actor, view.id(), "Cheque 0012345 rejected", true);
        assertThat(a.getStatus()).isEqualTo(InvoiceStatus.DRAFT);
    }

    @Test
    void chequeDetailsOnANonChequePaymentAreRefused() {
        assertThatThrownBy(() -> service.record(practice, actor, patient, new RecordReceipt(new BigDecimal("100"), PaymentMethod.CASH,
                null, null, null, null, List.of(), false, new ChequeInfo("1", null, null, LocalDate.now()))))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void theAccountSummarisesWhatIsOwedAndWhatIsHeld() {
        Invoice a = invoice("1000", LocalDate.of(2026, 9, 1));
        Invoice cancelled = invoice("999", LocalDate.of(2026, 9, 2));
        cancelled.setStatus(InvoiceStatus.CANCELLED);
        service.record(practice, actor, patient, receipt("400", false, new Allocation(a.getId(), new BigDecimal("300"))));

        Account account = service.account(practice, patient);

        assertThat(account.totalInvoiced()).isEqualByComparingTo("1000");
        assertThat(account.totalPaid()).isEqualByComparingTo("300");
        assertThat(account.balanceDue()).isEqualByComparingTo("700");
        assertThat(account.creditAvailable()).isEqualByComparingTo("100");
        assertThat(account.net()).isEqualByComparingTo("600");
    }
}
