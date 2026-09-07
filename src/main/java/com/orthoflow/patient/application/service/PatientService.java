package com.orthoflow.patient.application.service;

import com.orthoflow.patient.application.dto.CreatePatientRequest;
import com.orthoflow.patient.application.dto.PatientResponse;
import com.orthoflow.patient.application.dto.UpdatePatientRequest;
import com.orthoflow.patient.application.port.InvoiceLinkGuard;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PatientService {

    private final PatientRepository patientRepository;
    private final InvoiceLinkGuard invoiceLinkGuard;

    /**
     * A CIN matching an existing (non-archived) patient is almost always the
     * same person registered twice by mistake — the frontend previously had
     * no duplicate check at all (audit VIII.4). Phone is deliberately not
     * checked: guardians and family members legitimately share one number.
     * Email is skipped too: it's optional and the DB unique constraint
     * already rejects a real collision with a clear error.
     *
     * <p>The request is a whitelist DTO, not the JPA entity — see
     * {@link CreatePatientRequest}. The client cannot set {@code id},
     * {@code deletedAt}, {@code consentGivenAt}, {@code version} or
     * {@code createdAt} through this path any more.
     */
    @Transactional
    public PatientResponse createPatient(CreatePatientRequest request) {
        if (request.getCin() != null && !request.getCin().isBlank()
                && patientRepository.existsByCin(request.getCin())) {
            throw new ConflictException("A patient with CIN " + request.getCin() + " already exists");
        }

        Patient patient = Patient.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .dateOfBirth(request.getDateOfBirth())
                .gender(normaliseGender(request.getGender()))
                .email(blankToNull(request.getEmail()))
                .phone(request.getPhone())
                .address(request.getAddress())
                .cin(blankToNull(request.getCin()))
                .guardianName(request.getGuardianName())
                .guardianPhone(request.getGuardianPhone())
                .insuranceProvider(request.getInsuranceProvider())
                .insuranceNumber(request.getInsuranceNumber())
                .status(request.getStatus() == null ? "ACTIVE" : request.getStatus())
                .build();

        return PatientResponse.from(patientRepository.save(patient));
    }

    @Transactional(readOnly = true)
    public Page<PatientResponse> getAllPatients(Pageable pageable, String search) {
        Page<Patient> page = (search == null || search.isBlank())
                ? patientRepository.findAll(pageable)
                : patientRepository.search(search.trim(), pageable);
        return page.map(PatientResponse::from);
    }

    @Transactional(readOnly = true)
    public PatientResponse getPatient(UUID id) {
        return PatientResponse.from(getPatientById(id));
    }

    /** Entity accessor for callers inside the domain (delete, erase, update). */
    @Transactional(readOnly = true)
    public Patient getPatientById(UUID id) {
        return patientRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Patient not found"));
    }

    @Transactional
    public PatientResponse updatePatient(UUID id, UpdatePatientRequest request) {
        Patient existing = getPatientById(id);
        existing.setFirstName(request.getFirstName());
        existing.setLastName(request.getLastName());
        existing.setDateOfBirth(request.getDateOfBirth());
        existing.setGender(normaliseGender(request.getGender()));
        existing.setEmail(blankToNull(request.getEmail()));
        existing.setPhone(request.getPhone());
        existing.setAddress(request.getAddress());
        existing.setCin(blankToNull(request.getCin()));
        existing.setGuardianName(request.getGuardianName());
        existing.setGuardianPhone(request.getGuardianPhone());
        existing.setInsuranceProvider(request.getInsuranceProvider());
        existing.setInsuranceNumber(request.getInsuranceNumber());
        if (request.getStatus() != null) {
            existing.setStatus(request.getStatus());
        }
        return PatientResponse.from(patientRepository.save(existing));
    }

    /**
     * Archives a patient (deleted_at/deleted_by) instead of deleting the
     * row. A real DELETE cascaded to appointments and treatment history with
     * no way to satisfy a statutory retention obligation afterwards (audit
     * II.15). See erasePatient for the genuinely destructive GDPR path.
     */
    @Transactional
    public void deletePatient(UUID id, UUID actorId) {
        Patient patient = getPatientById(id);
        patient.setDeletedAt(OffsetDateTime.now());
        patient.setDeletedBy(actorId);
        patientRepository.save(patient);
    }

    /**
     * Permanently removes a patient and (via DB cascade) their appointments
     * and treatment history. Restricted to ADMIN at the controller — this is
     * the dedicated, audited path for a GDPR/Law 09-08 erasure request, not
     * the everyday "delete patient" action.
     *
     * <p>Invoices reference the patient with {@code ON DELETE RESTRICT}
     * (migration V15, deliberately): a financial record carries its own
     * accounting-law retention obligation independent of the patient's
     * erasure right, so it must not be destroyed or orphaned as a side
     * effect. If any invoice still points at this patient the erasure is
     * refused with an explanation — the operator anonymises or archives those
     * invoices first, then retries — rather than failing with the raw
     * FK-violation 409 the caller used to get.
     */
    @Transactional
    public void erasePatient(UUID id) {
        getPatientById(id); // 404 if unknown, before we check anything else

        long invoiceCount = invoiceLinkGuard.countInvoicesForPatient(id);
        if (invoiceCount > 0) {
            throw new ConflictException(
                    "This patient still has " + invoiceCount + " invoice(s). Invoices carry their own "
                    + "accounting-law retention obligation and are not removed by an erasure request. "
                    + "Anonymise or archive those invoices first, then retry the erasure.");
        }

        patientRepository.deleteById(id);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String normaliseGender(String g) {
        return g == null || g.isBlank() ? null : g.trim().toUpperCase();
    }
}
