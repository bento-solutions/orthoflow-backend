package com.orthoflow.help.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Documentation for one screen in one language. A null {@code practiceId} is a note
 * shipped with the product; a clinic changes one by saving a row of its own for the
 * same page and language, and removes its change to get the original back.
 */
@Entity
@Table(name = "help_notes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HelpNote {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id")
    private UUID practiceId;

    @Column(name = "page_key", nullable = false)
    private String pageKey;

    @Column(nullable = false)
    private String lang;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (id == null) id = UUID.randomUUID();
        updatedAt = OffsetDateTime.now();
    }

    public boolean isBuiltIn() {
        return practiceId == null;
    }
}
