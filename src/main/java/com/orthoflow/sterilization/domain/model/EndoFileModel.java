package com.orthoflow.sterilization.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A kind of endodontic file and how many times one may be used before it is thrown away. */
@Entity
@Table(name = "endo_file_models")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EndoFileModel {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(nullable = false)
    private String name;

    private String brand;

    @Column(name = "size_taper")
    private String sizeTaper;

    @Column(name = "max_uses", nullable = false)
    @Builder.Default
    private int maxUses = 1;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
