package com.orthoflow.sterilization.infrastructure;

import com.orthoflow.sterilization.domain.model.EndoFile;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndoFileJpaRepository extends JpaRepository<EndoFile, UUID> {

    Optional<EndoFile> findByIdAndPracticeId(UUID id, UUID practiceId);

    List<EndoFile> findByKitItemIdAndDiscardedAtIsNullOrderByCreatedAtAsc(UUID kitItemId);

    List<EndoFile> findByKitItemIdInAndDiscardedAtIsNull(Collection<UUID> kitItemIds);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT f FROM EndoFile f WHERE f.kitItemId = :kit AND f.discardedAt IS NULL")
    List<EndoFile> lockActiveFiles(@Param("kit") UUID kitItemId);
}
