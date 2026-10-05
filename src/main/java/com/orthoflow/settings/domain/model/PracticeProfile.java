package com.orthoflow.settings.domain.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The clinic's identity: what is printed on every invoice, fee note and report.
 * It used to live in each staff PC's localStorage (CabinetService), so a
 * document printed from another machine lost its legal details. Maps the
 * {@code practices} table that tenancy (V32) introduced.
 */
@Entity
@Table(name = "practices")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PracticeProfile {

    @Id
    private UUID id;

    @Version
    private Long version;

    @Column(nullable = false)
    private String name;

    @Column(name = "legal_name")
    private String legalName;

    private String ice;

    @Column(name = "tax_id")
    private String taxId;

    private String patente;

    @Column(name = "cnss_number")
    private String cnssNumber;

    private String rib;

    private String inpe;

    @Column(columnDefinition = "TEXT")
    private String address;

    private String city;

    private String phone;

    private String email;

    private String website;

    @Column(name = "logo_file_id")
    private UUID logoFileId;

    @Column(nullable = false, length = 3)
    @Builder.Default
    private String currency = "MAD";

    @Column(nullable = false)
    @Builder.Default
    private String timezone = "Africa/Casablanca";

    @Column(name = "default_language", nullable = false, length = 2)
    @Builder.Default
    private String defaultLanguage = "fr";

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = OffsetDateTime.now();
    }
}
