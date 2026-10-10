package com.orthoflow.patient.application.service;

import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.patient.application.dto.PatientExtras;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.model.PatientPhone;
import com.orthoflow.patient.infrastructure.adapter.persistence.InsurerJpaRepository;
import com.orthoflow.patient.infrastructure.adapter.persistence.PatientPhoneJpaRepository;
import com.orthoflow.team.application.service.PractitionerService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Applies the front-desk fields to a patient, validating what they point at
 * (an insurer or practitioner of this clinic, a code nobody else holds).
 * Absent fields change nothing, so an older client that never sends them cannot
 * wipe them.
 */
@Component
@RequiredArgsConstructor
public class PatientExtrasApplier {

    private final PatientCodeGenerator codes;
    private final PatientPhoneJpaRepository phones;
    private final InsurerJpaRepository insurers;
    private final PractitionerService practitionerService;

    /** For a new patient: the typed code if it is free, else the next generated one. */
    public void applyOnCreate(Patient patient, PatientExtras extras, UUID practiceId) {
        String typed = blankToNull(extras.getPatientCode());
        if (typed != null) {
            if (codes.taken(practiceId, typed, null)) {
                throw new ConflictException("Patient code " + typed + " is already in use");
            }
            patient.setPatientCode(typed);
        } else {
            patient.setPatientCode(codes.next());
        }
        apply(patient, extras, practiceId);
    }

    /** A code for a patient made outside the form (a booking, a self-registration). */
    public void assignCode(Patient patient) {
        patient.setPatientCode(codes.next());
    }

    public void applyOnUpdate(Patient patient, PatientExtras extras, UUID practiceId) {
        String typed = blankToNull(extras.getPatientCode());
        if (typed != null && !typed.equalsIgnoreCase(patient.getPatientCode())) {
            if (codes.taken(practiceId, typed, patient.getId())) {
                throw new ConflictException("Patient code " + typed + " is already in use");
            }
            patient.setPatientCode(typed);
        }
        apply(patient, extras, practiceId);
    }

    private void apply(Patient patient, PatientExtras e, UUID practiceId) {
        if (e.getOccupation() != null) patient.setOccupation(blankToNull(e.getOccupation()));
        if (e.getReferralSource() != null) patient.setReferralSource(blankToNull(e.getReferralSource()));
        if (e.getGlobalDiscountPct() != null) patient.setGlobalDiscountPct(e.getGlobalDiscountPct());
        if (e.getPreferredLanguage() != null) patient.setPreferredLanguage(e.getPreferredLanguage());
        if (e.getPhotoFileId() != null) patient.setPhotoFileId(e.getPhotoFileId());
        if (e.getInsurerId() != null) {
            insurers.findByIdAndPracticeId(e.getInsurerId(), practiceId)
                    .orElseThrow(() -> new NotFoundException("Insurer not found"));
            patient.setInsurerId(e.getInsurerId());
        }
        if (e.getInsuranceAffiliationNumber() != null) patient.setInsuranceAffiliationNumber(blankToNull(e.getInsuranceAffiliationNumber()));
        if (e.getInsuredRelation() != null) patient.setInsuredRelation(e.getInsuredRelation());
        if (e.getInsuredName() != null) patient.setInsuredName(blankToNull(e.getInsuredName()));
        if (e.getInsuredCin() != null) patient.setInsuredCin(blankToNull(e.getInsuredCin()));
        if (e.getPrimaryPractitionerId() != null) {
            practitionerService.require(practiceId, e.getPrimaryPractitionerId());
            patient.setPrimaryPractitionerId(e.getPrimaryPractitionerId());
        }
        if (patient.getGlobalDiscountPct() == null) patient.setGlobalDiscountPct(BigDecimal.ZERO);
    }

    /** Replaces the patient's extra numbers when the request carried a list, even an empty one. */
    @Transactional
    public void replacePhones(UUID patientId, List<PatientExtras.PhoneEntry> numbers) {
        if (numbers == null) {
            return;
        }
        phones.deleteByPatientId(patientId);
        phones.flush();
        numbers.forEach(n -> phones.save(PatientPhone.builder().patientId(patientId).number(n.number().trim())
                .label(blankToNull(n.label())).build()));
    }

    @Transactional(readOnly = true)
    public List<PatientExtras.PhoneEntry> phonesOf(UUID patientId) {
        return phones.findByPatientIdOrderByCreatedAtAsc(patientId).stream()
                .map(p -> new PatientExtras.PhoneEntry(p.getNumber(), p.getLabel())).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
