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

    @Override
    @Transactional(readOnly = true)
    public Optional<PatientSummary> findSummary(UUID patientId) {
        return patientRepository.findById(patientId).map(PatientLookupService::toSummary);
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
    public Optional<UUID> findIdByPhoneDigits(String digits) {
        if (digits == null || digits.length() < 9) {
            return Optional.empty();
        }
        List<UUID> matches = patientRepository.findIdsByPhoneSuffix(digits.substring(digits.length() - 9));
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private static PatientSummary toSummary(Patient patient) {
        return new PatientSummary(
                patient.getId(),
                patient.getFirstName(),
                patient.getLastName(),
                patient.getEmail(),
                patient.getPhone(),
                patient.getCin());
    }
}
