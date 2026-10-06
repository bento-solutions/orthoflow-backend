package com.orthoflow.sterilization.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One physical file in an endo kit, counting its own uses. */
@Entity
@Table(name = "endo_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndoFile {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(name = "model_id", nullable = false)
    private UUID modelId;

    @Column(name = "kit_item_id", nullable = false)
    private UUID kitItemId;

    @Column(name = "use_count", nullable = false)
    private int useCount;

    @Column(name = "discarded_at")
    private OffsetDateTime discardedAt;

    @Column(name = "discard_reason")
    private String discardReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }

    public boolean isDiscarded() {
        return discardedAt != null;
    }
}
