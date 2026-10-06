package com.orthoflow.billing.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.billing.domain.model.Cheque;
import com.orthoflow.billing.infrastructure.adapter.persistence.ChequeJpaRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The cheque register. A cheque is PENDING when received, DEPOSITED when it goes
 * to the bank, then CASHED — or REJECTED, which reverses the receipt it was taken
 * against (the patient owes the money again) and alerts the people who manage
 * finance. A guarantee cheque is held as security and has no receipt.
 */
@Service
@RequiredArgsConstructor
public class ChequeService {

    @io.swagger.v3.oas.annotations.media.Schema(name = "ChequeCreate")
    public record Create(@NotBlank @Size(max = 40) String number, @Size(max = 100) String bank,
                         @Size(max = 200) String drawerName, UUID patientId, @NotNull @DecimalMin("0.01") BigDecimal amount,
                         @NotNull LocalDate dueDate, boolean guarantee, String notes) {
    }

    @io.swagger.v3.oas.annotations.media.Schema(name = "ChequeView")
    public record View(UUID id, String number, String bank, String drawerName, UUID patientId, String patientName,
                       BigDecimal amount, LocalDate dueDate, LocalDate depositDate, LocalDate cashedDate,
                       Cheque.Status status, boolean guarantee, UUID receiptId, String notes, boolean postDated) {
    }

    private final ChequeJpaRepository cheques;
    private final ReceiptService receiptService;
    private final PatientLookup patientLookup;
    private final StaffNotifier notifier;
    private final PracticeZone practiceZone;

    @Transactional
    public View create(UUID practiceId, UUID actorId, Create r) {
        if (r.patientId() != null && !patientLookup.exists(r.patientId())) {
            throw new NotFoundException("Patient not found");
        }
        if (!r.guarantee()) {
            // A cheque that pays something is recorded as a receipt, which creates it; a standalone one is a guarantee.
            throw new ValidationException("Record a cheque payment as a receipt; only a guarantee cheque is entered here");
        }
        Cheque saved = cheques.save(Cheque.builder().practiceId(practiceId).number(r.number()).bank(r.bank())
                .drawerName(r.drawerName()).patientId(r.patientId()).amount(r.amount()).dueDate(r.dueDate())
                .guarantee(true).notes(r.notes()).createdBy(actorId).build());
        return view(saved, today(practiceId));
    }

    @Transactional(readOnly = true)
    public List<View> search(UUID practiceId, Cheque.Status status, UUID patientId, Boolean guarantee, LocalDate dueTo) {
        LocalDate today = today(practiceId);
        return cheques.search(practiceId, status, patientId, guarantee, dueTo).stream().map(c -> view(c, today)).toList();
    }

    @Transactional
    public View deposit(UUID practiceId, UUID id, LocalDate date) {
        Cheque c = require(practiceId, id);
        if (c.getStatus() != Cheque.Status.PENDING) {
            throw new ConflictException("Only a pending cheque can be deposited (it is " + c.getStatus() + ")");
        }
        c.setStatus(Cheque.Status.DEPOSITED);
        c.setDepositDate(date != null ? date : today(practiceId));
        return view(c, today(practiceId));
    }

    @Transactional
    public View cash(UUID practiceId, UUID id, LocalDate date) {
        Cheque c = require(practiceId, id);
        if (c.getStatus() != Cheque.Status.DEPOSITED) {
            throw new ConflictException("Only a deposited cheque can be marked cashed (it is " + c.getStatus() + ")");
        }
        c.setStatus(Cheque.Status.CASHED);
        c.setCashedDate(date != null ? date : today(practiceId));
        return view(c, today(practiceId));
    }

    @Transactional
    public View reject(UUID practiceId, UUID actorId, UUID id, String reason) {
        Cheque c = require(practiceId, id);
        if (c.getStatus() == Cheque.Status.CASHED || c.getStatus() == Cheque.Status.REJECTED) {
            throw new ConflictException("A cheque that is " + c.getStatus() + " cannot be rejected");
        }
        c.setStatus(Cheque.Status.REJECTED);
        if (reason != null && !reason.isBlank()) {
            c.setNotes((c.getNotes() == null ? "" : c.getNotes() + "\n") + "Rejected: " + reason.trim());
        }
        if (c.getReceiptId() != null) {
            receiptService.voidReceipt(practiceId, actorId, c.getReceiptId(), "Cheque " + c.getNumber() + " rejected", true);
        }
        PatientSummary patient = c.getPatientId() == null ? null : patientLookup.findSummary(c.getPatientId()).orElse(null);
        notifier.toPermission(practiceId, Permission.FINANCE_MANAGE, MessagePurpose.CHEQUE_REJECTED,
                "Chèque rejeté",
                "Le chèque n° " + c.getNumber() + " de " + c.getAmount() + (patient == null ? "" : " (" + patient.fullName() + ")")
                        + " a été rejeté" + (c.getReceiptId() != null ? "; le règlement a été annulé." : "."),
                "CHEQUE", c.getId());
        return view(c, today(practiceId));
    }

    /** A guarantee cheque handed back to the patient before it was ever deposited. */
    @Transactional
    public void release(UUID practiceId, UUID id) {
        Cheque c = require(practiceId, id);
        if (!c.isGuarantee() || c.getStatus() != Cheque.Status.PENDING) {
            throw new ConflictException("Only a guarantee cheque that has not been deposited can be released");
        }
        cheques.delete(c);
    }

    private Cheque require(UUID practiceId, UUID id) {
        return cheques.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Cheque not found"));
    }

    private LocalDate today(UUID practiceId) {
        return LocalDate.now(practiceZone.of(practiceId));
    }

    private View view(Cheque c, LocalDate today) {
        String name = c.getPatientId() == null ? null : patientLookup.findSummary(c.getPatientId()).map(PatientSummary::fullName).orElse(null);
        return new View(c.getId(), c.getNumber(), c.getBank(), c.getDrawerName(), c.getPatientId(), name, c.getAmount(),
                c.getDueDate(), c.getDepositDate(), c.getCashedDate(), c.getStatus(), c.isGuarantee(), c.getReceiptId(),
                c.getNotes(), c.getStatus() == Cheque.Status.PENDING && c.getDueDate().isAfter(today));
    }
}
