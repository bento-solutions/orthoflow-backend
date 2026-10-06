package com.orthoflow.sterilization.infrastructure;

import com.orthoflow.sterilization.domain.model.SterilizationItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SterilizationItemJpaRepository extends JpaRepository<SterilizationItem, UUID> {

    Optional<SterilizationItem> findByIdAndPracticeId(UUID id, UUID practiceId);

    Optional<SterilizationItem> findByQrTokenAndPracticeId(String qrToken, UUID practiceId);

    boolean existsByPracticeIdAndCodeIgnoreCase(UUID practiceId, String code);

    Optional<SterilizationItem> findByPracticeIdAndCodeIgnoreCase(UUID practiceId, String code);

    List<SterilizationItem> findByPracticeIdOrderByCodeAsc(UUID practiceId);

    /** Row-locked, so two people scanning the same tray cannot both move it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SterilizationItem i WHERE i.id = :id AND i.practiceId = :practiceId")
    Optional<SterilizationItem> findForUpdate(@Param("id") UUID id, @Param("practiceId") UUID practiceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT i FROM SterilizationItem i WHERE i.id IN :ids AND i.practiceId = :practiceId ORDER BY i.code")
    List<SterilizationItem> findAllForUpdate(@Param("ids") Collection<UUID> ids, @Param("practiceId") UUID practiceId);

    List<SterilizationItem> findByPracticeIdAndLastCycleId(UUID practiceId, UUID lastCycleId);
}
