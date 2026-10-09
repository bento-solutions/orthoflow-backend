package com.orthoflow.patient.domain.repository;

import com.orthoflow.patient.domain.model.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientRepository {
    Patient save(Patient patient);
    /** Writes the row now, for a caller that goes on to reference it from plain SQL in the same transaction. */
    Patient saveAndFlush(Patient patient);
    Optional<Patient> findById(UUID id);
    List<Patient> findAll();
    List<Patient> findAllById(List<UUID> ids);
    boolean existsById(UUID id);
    Page<Patient> findAll(Pageable pageable);
    Page<Patient> search(String term, Pageable pageable);
    boolean existsByEmailIgnoreCase(String email);
    boolean existsByCin(String cin);
    boolean existsByPhone(String phone);
    List<UUID> findIdsByPhoneSuffix(UUID practiceId, String lastDigits);
    void deleteById(UUID id);
}
