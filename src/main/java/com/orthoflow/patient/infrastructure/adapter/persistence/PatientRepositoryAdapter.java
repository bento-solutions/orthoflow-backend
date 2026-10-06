package com.orthoflow.patient.infrastructure.adapter.persistence;

import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class PatientRepositoryAdapter implements PatientRepository {

    private final PatientJpaRepository jpaRepository;

    @Override
    public Patient save(Patient patient) {
        return jpaRepository.save(patient);
    }

    @Override
    public Patient saveAndFlush(Patient patient) {
        return jpaRepository.saveAndFlush(patient);
    }

    @Override
    public Optional<Patient> findById(UUID id) {
        return jpaRepository.findById(id);
    }

    @Override
    public List<Patient> findAll() {
        return jpaRepository.findAll();
    }

    @Override
    public Page<Patient> findAll(Pageable pageable) {
        return jpaRepository.findAll(pageable);
    }

    @Override
    public List<Patient> findAllById(List<UUID> ids) {
        return jpaRepository.findAllById(ids);
    }

    @Override
    public boolean existsById(UUID id) {
        return jpaRepository.existsById(id);
    }

    @Override
    public Page<Patient> search(String term, Pageable pageable) {
        return jpaRepository.search(term, pageable);
    }

    @Override
    public boolean existsByEmailIgnoreCase(String email) {
        return jpaRepository.existsByEmailIgnoreCase(email);
    }

    @Override
    public boolean existsByCin(String cin) {
        return jpaRepository.existsByCin(cin);
    }

    @Override
    public boolean existsByPhone(String phone) {
        return jpaRepository.existsByPhone(phone);
    }

    @Override
    public void deleteById(UUID id) {
        jpaRepository.hardDeleteById(id);
    }

    @Override
    public java.util.List<UUID> findIdsByPhoneSuffix(String lastDigits) {
        return jpaRepository.findIdsByPhoneSuffix(lastDigits);
    }
}
