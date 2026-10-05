package com.orthoflow.storage.infrastructure;

import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StoredFileJpaRepository extends JpaRepository<StoredFile, UUID> {

    Optional<StoredFile> findByIdAndPracticeIdAndDeletedAtIsNull(UUID id, UUID practiceId);

    List<StoredFile> findByPracticeIdAndOwnerTypeAndOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(
            UUID practiceId, FileOwnerType ownerType, UUID ownerId);
}
