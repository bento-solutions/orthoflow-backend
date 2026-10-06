package com.orthoflow.sterilization.infrastructure;

import com.orthoflow.sterilization.domain.model.Autoclave;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AutoclaveJpaRepository extends JpaRepository<Autoclave, UUID> {

    Optional<Autoclave> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<Autoclave> findByPracticeIdOrderByNameAsc(UUID practiceId);

    boolean existsByPracticeIdAndNameIgnoreCase(UUID practiceId, String name);

    /** Held while a cycle number is allocated so two loads started together get different numbers. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Autoclave a WHERE a.id = :id AND a.practiceId = :practiceId")
    Optional<Autoclave> findForUpdate(@Param("id") UUID id, @Param("practiceId") UUID practiceId);
}
