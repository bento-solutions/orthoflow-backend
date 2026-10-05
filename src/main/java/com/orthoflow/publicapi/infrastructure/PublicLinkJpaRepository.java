package com.orthoflow.publicapi.infrastructure;

import com.orthoflow.publicapi.domain.model.PublicLink;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PublicLinkJpaRepository extends JpaRepository<PublicLink, UUID> {

    Optional<PublicLink> findByTokenHash(String tokenHash);

    List<PublicLink> findByPracticeIdAndPurposeAndSubjectTypeAndRevokedAtIsNull(
            UUID practiceId, PublicLinkPurpose purpose, String subjectType);

    /** Takes one use atomically: two simultaneous submissions cannot both pass a max-uses cap of one. */
    @Modifying
    @Query("UPDATE PublicLink l SET l.uses = l.uses + 1 WHERE l.id = :id AND (l.maxUses IS NULL OR l.uses < l.maxUses)")
    int consume(@Param("id") UUID id);
}
