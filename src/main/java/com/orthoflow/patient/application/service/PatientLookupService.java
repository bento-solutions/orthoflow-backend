package com.orthoflow.patient.application.service;

import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The only implementation of {@link PatientLookup} — every other module
 * depends on the interface, never on this class or on
 * {@code patient.domain.model.Patient} directly (audit I.2).
 */
@Service
@RequiredArgsConstructor
public class PatientLookupService implements PatientLookup {

    private final PatientRepository patientRepository;
    private final com.orthoflow.patient.infrastructure.adapter.persistence.InsurerJpaRepository insurers;

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientSummary> findSummary(UUID patientId) {
        return patientRepository.findById(patientId).map(PatientLookupService::toSummary);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<com.orthoflow.patient.application.port.PatientIdentity> findIdentity(UUID patientId) {
        return patientRepository.findById(patientId).map(p -> {
            // The linked insurer's name wins over the free text typed before insurers existed.
            String insurer = p.getInsurerId() == null ? p.getInsuranceProvider()
                    : insurers.findById(p.getInsurerId()).map(com.orthoflow.patient.domain.model.Insurer::getName).orElse(p.getInsuranceProvider());
            return new com.orthoflow.patient.application.port.PatientIdentity(p.getId(), p.getPatientCode(), p.getFirstName(),
                    p.getLastName(), p.getDateOfBirth(), p.getGender(), p.getCin(), p.getAddress(), p.getPhone(), p.getEmail(),
                    insurer, p.getInsuranceNumber(), p.getInsurerId(), p.getGuardianName(), p.getInsuranceAffiliationNumber(),
                    p.getInsuredRelation(), p.getInsuredName(), p.getInsuredCin());
        });
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, PatientSummary> findSummaries(List<UUID> patientIds) {
        return patientRepository.findAllById(patientIds).stream()
                .collect(Collectors.toMap(Patient::getId, PatientLookupService::toSummary));
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID patientId) {
        return patientRepository.existsById(patientId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByPhoneDigits(UUID practiceId, String digits) {
        if (digits == null || digits.length() < 9) {
            return Optional.empty();
        }
        List<UUID> matches = patientRepository.findIdsByPhoneSuffix(practiceId, digits.substring(digits.length() - 9));
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findPrimaryPractitionerId(UUID patientId) {
        return patientRepository.findById(patientId).map(Patient::getPrimaryPractitionerId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<com.orthoflow.patient.application.port.InsurerRef> findInsurerOf(UUID patientId) {
        return patientRepository.findById(patientId).flatMap(p -> {
            if (p.getInsurerId() != null) {
                return insurers.findById(p.getInsurerId());
            }
            String typed = p.getInsuranceProvider() == null ? "" : p.getInsuranceProvider().trim();
            if (typed.isEmpty()) {
                return Optional.empty();
            }
            return insurers.findByPracticeIdOrderByNameAsc(p.getPracticeId()).stream()
                    .filter(i -> typed.equalsIgnoreCase(i.getCode()) || typed.equalsIgnoreCase(i.getName()))
                    .findFirst();
        }).map(i -> new com.orthoflow.patient.application.port.InsurerRef(i.getId(), i.getCode(), i.getName(), i.getKind(),
                i.getFormCode()));
    }

    private static PatientSummary toSummary(Patient patient) {
        return new PatientSummary(
                patient.getId(),
                patient.getFirstName(),
                patient.getLastName(),
                patient.getEmail(),
                patient.getPhone(),
                patient.getCin(),
                patient.getPreferredLanguage());
    }
}
