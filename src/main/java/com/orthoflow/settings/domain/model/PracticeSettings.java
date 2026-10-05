package com.orthoflow.settings.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The clinic-wide working hours (audit VIII.6 / P2 #29). One row per clinic;
 * the existing singleton belongs to the default clinic. Per-weekday opening
 * hours live in {@link OpeningHours} and keep these two numbers in step.
 */
@Entity
@Table(name = "practice_settings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PracticeSettings {

    public static final UUID SINGLETON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Id
    private UUID id;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = com.orthoflow.common.tenancy.Practices.DEFAULT_ID;

    @Column(name = "working_hours_start", nullable = false)
    private Short workingHoursStart;

    @Column(name = "working_hours_end", nullable = false)
    private Short workingHoursEnd;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @PrePersist
    @PreUpdate
    public void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
