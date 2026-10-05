package com.orthoflow.auth.infrastructure.adapter.persistence;

import com.orthoflow.auth.domain.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface UserSessionJpaRepository extends JpaRepository<UserSession, UUID> {

    List<UserSession> findByUserIdAndRevokedAtIsNullOrderByLastSeenAtDesc(UUID userId);

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL AND s.id <> :keep")
    int revokeOthers(@Param("userId") UUID userId, @Param("keep") UUID keep, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE UserSession s SET s.revokedAt = :now WHERE s.userId = :userId AND s.revokedAt IS NULL")
    int revokeAll(@Param("userId") UUID userId, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("UPDATE UserSession s SET s.lastSeenAt = :now WHERE s.id = :id AND s.lastSeenAt < :before")
    int touch(@Param("id") UUID id, @Param("now") OffsetDateTime now, @Param("before") OffsetDateTime before);
}
