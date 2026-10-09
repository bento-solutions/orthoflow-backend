package com.orthoflow.patient.application.service;

import com.orthoflow.patient.application.dto.CreatePatientRequest;
import com.orthoflow.patient.application.dto.PatientDemographicsUpdate;
import com.orthoflow.patient.application.dto.PatientResponse;
import com.orthoflow.patient.application.dto.UpdatePatientRequest;
import com.orthoflow.patient.application.port.InvoiceLinkGuard;
import com.orthoflow.patient.application.port.PatientErasureListener;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.common.exception.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PatientService implements com.orthoflow.patient.application.port.PatientRegistrar {

    private final PatientRepository patientRepository;
    private final InvoiceLinkGuard invoiceLinkGuard;
    private final List<PatientErasureListener> erasureListeners;
    private final PatientExtrasApplier extras;
    private final CurrentUserProvider currentUser;
    private final com.orthoflow.common.tenancy.CurrentPractice currentPractice;

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

        UUID practiceId = currentUser.requirePracticeId();
        Patient patient = Patient.builder()
                .practiceId(practiceId)
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
        extras.applyOnCreate(patient, request, practiceId);

        Patient saved = patientRepository.save(patient);
        extras.replacePhones(saved.getId(), request.getPhones());
        return PatientResponse.from(saved).withPhones(extras.phonesOf(saved.getId()));
    }

    @Transactional
    public void setPhoto(UUID patientId, UUID fileId) {
        Patient patient = getPatientById(patientId);
        patient.setPhotoFileId(fileId);
        patientRepository.save(patient);
    }

    @Override
    @Transactional
    public UUID registerWalkIn(String firstName, String lastName, String phone) {
        CreatePatientRequest request = new CreatePatientRequest();
        request.setFirstName(firstName.trim());
        request.setLastName(lastName.trim());
        request.setPhone(blankToNull(phone));
        return createPatient(request).id();
    }

    @Override
    @Transactional
    public UUID register(Registration r) {
        // The clinic in scope, signed in or not: an online booking auto-confirmed from the public page has no user.
        UUID practiceId = currentPractice.require();
        return register(practiceId, r);
    }

    @Override
    @Transactional
    public void recordAcquisition(UUID patientId, String channel) {
        patientRepository.findById(patientId).filter(p -> p.getAcquisitionChannel() == null).ifPresent(p -> {
            p.setAcquisitionChannel(channel);
            patientRepository.save(p);
        });
    }

    /** As {@link #register(Registration)} for a caller with no signed-in user (an auto-confirmed online booking). */
    @Transactional
    public UUID register(UUID practiceId, Registration r) {
        String cin = blankToNull(r.cin());
        if (cin != null && patientRepository.existsByCin(cin)) {
            throw new ConflictException("A patient with CIN " + cin + " already exists");
        }
        String email = blankToNull(r.email());
        if (email != null && patientRepository.existsByEmailIgnoreCase(email)) {
            email = null;
        }
        Patient patient = Patient.builder().practiceId(practiceId).firstName(r.firstName().trim()).lastName(r.lastName().trim())
                .gender(normaliseGender(r.gender())).dateOfBirth(r.dateOfBirth()).phone(blankToNull(r.phone())).email(email)
                .address(blankToNull(r.address())).cin(cin).guardianName(blankToNull(r.guardianName()))
                .guardianPhone(blankToNull(r.guardianPhone())).insuranceProvider(blankToNull(r.insuranceProvider()))
                .insuranceNumber(blankToNull(r.insuranceNumber())).occupation(blankToNull(r.occupation()))
                .preferredLanguage(r.language() == null ? "fr" : r.language()).consentGivenAt(r.consentedAt()).status("ACTIVE").build();
        extras.assignCode(patient);
        // Flushed: callers record consent with plain SQL straight afterwards, which needs the row to exist.
        return patientRepository.saveAndFlush(patient).getId();
    }

    @Override
    @Transactional
    public void enrich(UUID patientId, Registration r) {
        Patient p = getPatientById(patientId);
        if (blankToNull(p.getPhone()) == null) p.setPhone(blankToNull(r.phone()));
        String email = blankToNull(r.email());
        if (blankToNull(p.getEmail()) == null && email != null && !patientRepository.existsByEmailIgnoreCase(email)) p.setEmail(email);
        if (p.getDateOfBirth() == null) p.setDateOfBirth(r.dateOfBirth());
        if (blankToNull(p.getGender()) == null) p.setGender(normaliseGender(r.gender()));
        if (blankToNull(p.getAddress()) == null) p.setAddress(blankToNull(r.address()));
        String cin = blankToNull(r.cin());
        if (blankToNull(p.getCin()) == null && cin != null && !patientRepository.existsByCin(cin)) p.setCin(cin);
        if (blankToNull(p.getGuardianName()) == null) p.setGuardianName(blankToNull(r.guardianName()));
        if (blankToNull(p.getGuardianPhone()) == null) p.setGuardianPhone(blankToNull(r.guardianPhone()));
        if (blankToNull(p.getInsuranceProvider()) == null) p.setInsuranceProvider(blankToNull(r.insuranceProvider()));
        if (blankToNull(p.getInsuranceNumber()) == null) p.setInsuranceNumber(blankToNull(r.insuranceNumber()));
        if (blankToNull(p.getOccupation()) == null) p.setOccupation(blankToNull(r.occupation()));
        if (p.getConsentGivenAt() == null) p.setConsentGivenAt(r.consentedAt());
        patientRepository.save(p);
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
        return PatientResponse.from(getPatientById(id)).withPhones(extras.phonesOf(id));
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
        extras.applyOnUpdate(existing, request, existing.getPracticeId());
        Patient saved = patientRepository.save(existing);
        extras.replacePhones(saved.getId(), request.getPhones());
        return PatientResponse.from(saved).withPhones(extras.phonesOf(saved.getId()));
    }

    /**
     * Applies only the fields {@code changes} sets, leaving everything else as
     * it was. See {@link PatientDemographicsUpdate}.
     *
     * <p>A CIN already held by a different patient is refused, as it is at
     * registration: it is almost always the same person entered twice, and
     * silently giving two patients one identity card is worse than telling the
     * doctor at review.
     */
    @Transactional
    public PatientResponse applyDemographics(UUID id, PatientDemographicsUpdate changes) {
        Patient existing = getPatientById(id);
        if (changes.isEmpty()) {
            return PatientResponse.from(existing);
        }
        String cin = blankToNull(changes.cin());
        if (cin != null && !cin.equalsIgnoreCase(existing.getCin() == null ? "" : existing.getCin())
                && patientRepository.existsByCin(cin)) {
            throw new ConflictException("A different patient already has CIN " + cin
                    + ". Correct it, or open that patient's file.");
        }
        if (blankToNull(changes.firstName()) != null) existing.setFirstName(changes.firstName().trim());
        if (blankToNull(changes.lastName()) != null) existing.setLastName(changes.lastName().trim());
        if (changes.dateOfBirth() != null) existing.setDateOfBirth(changes.dateOfBirth());
        if (blankToNull(changes.gender()) != null) existing.setGender(normaliseGender(changes.gender()));
        if (blankToNull(changes.phone()) != null) existing.setPhone(changes.phone().trim());
        if (cin != null) existing.setCin(cin);
        if (blankToNull(changes.insuranceProvider()) != null) existing.setInsuranceProvider(changes.insuranceProvider().trim());
        if (blankToNull(changes.insuranceNumber()) != null) existing.setInsuranceNumber(changes.insuranceNumber().trim());
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
                    "This patient still has " + invoiceCount + " financial record(s) (invoices, receipts, plans, cheques). "
                    + "They carry their own accounting-law retention obligation and are not removed by an erasure request. "
                    + "Anonymise or archive those records first, then retry the erasure.");
        }

        // What the database cascade does not reach (see PatientErasureListener),
        // inside this transaction so a failure undoes the erasure as a whole.
        erasureListeners.forEach(listener -> listener.onPatientErased(id));

        patientRepository.deleteById(id);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String normaliseGender(String g) {
        return g == null || g.isBlank() ? null : g.trim().toUpperCase();
    }
}
