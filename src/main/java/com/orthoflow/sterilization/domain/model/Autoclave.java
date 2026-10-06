package com.orthoflow.sterilization.domain.model;

import com.orthoflow.common.tenancy.Practices;
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A steriliser. Cycles are numbered per machine, as the machine's own log is. */
@Entity
@Table(name = "autoclaves")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Autoclave {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(name = "practice_id", nullable = false)
    @Builder.Default
    private UUID practiceId = Practices.DEFAULT_ID;

    @Column(nullable = false)
    private String name;

    private String model;

    @Column(name = "serial_number")
    private String serialNumber;

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
