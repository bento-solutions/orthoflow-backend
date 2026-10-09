package com.orthoflow.settings.domain.model;

import org.hibernate.annotations.TenantId;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalTime;
import java.util.UUID;

/** One weekday's opening hours (ISO: 1 = Monday … 7 = Sunday), with an optional lunch break. */
@Entity
@Table(name = "practice_opening_hours")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpeningHours {

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(nullable = false)
    private short weekday;

    @Column(nullable = false)
    private boolean closed;

    @Column(name = "open_time", nullable = false)
    private LocalTime openTime;

    @Column(name = "close_time", nullable = false)
    private LocalTime closeTime;

    @Column(name = "break_start")
    private LocalTime breakStart;

    @Column(name = "break_end")
    private LocalTime breakEnd;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
    }
}
