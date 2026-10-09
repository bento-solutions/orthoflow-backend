package com.orthoflow.patient.infrastructure.adapter.persistence;

import com.orthoflow.patient.domain.model.Patient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import java.util.UUID;

public interface PatientJpaRepository extends JpaRepository<Patient, UUID> {

    /**
     * Bypasses the entity's {@code @SQLRestriction} (which hides archived
     * rows from normal queries) with a native statement — the GDPR erasure
     * path must be able to permanently remove a patient regardless of
     * whether it was already archived.
     */
    @Modifying
    @Query(value = "DELETE FROM patients WHERE id = :id AND practice_id = :practiceId", nativeQuery = true)
    void hardDeleteById(UUID id, UUID practiceId);

    @Query("SELECT p FROM Patient p WHERE " +
            "LOWER(p.firstName) LIKE LOWER(CONCAT('%', :term, '%')) OR " +
            "LOWER(p.lastName) LIKE LOWER(CONCAT('%', :term, '%')) OR " +
            "LOWER(p.email) LIKE LOWER(CONCAT('%', :term, '%')) OR " +
            "p.cin LIKE CONCAT('%', :term, '%') OR " +
            "p.phone LIKE CONCAT('%', :term, '%')")
    Page<Patient> search(String term, Pageable pageable);

    boolean existsByEmailIgnoreCase(String email);

    boolean existsByCin(String cin);

    boolean existsByPhone(String phone);

    @Query(value = "SELECT id FROM patients WHERE practice_id = :practiceId AND deleted_at IS NULL AND phone IS NOT NULL "
            + "AND right(regexp_replace(phone, '[^0-9]', '', 'g'), length(:lastDigits)) = :lastDigits", nativeQuery = true)
    java.util.List<UUID> findIdsByPhoneSuffix(UUID practiceId, String lastDigits);
}
