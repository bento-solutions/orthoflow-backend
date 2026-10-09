package com.orthoflow.publicapi.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "public_links")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PublicLink {

    public static final String SHARED = "SHARED";

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PublicLinkPurpose purpose;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "subject_type")
    private String subjectType;

    @Column(name = "subject_id")
    private UUID subjectId;

    @Column(name = "expires_at")
    private OffsetDateTime expiresAt;

    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(nullable = false)
    private int uses;

    @Column(nullable = false)
    private int rotation;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public boolean isUsable(OffsetDateTime now) {
        return revokedAt == null
                && (expiresAt == null || now.isBefore(expiresAt))
                && (maxUses == null || uses < maxUses);
    }
}
