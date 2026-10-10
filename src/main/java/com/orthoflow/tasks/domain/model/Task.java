package com.orthoflow.tasks.domain.model;

import org.hibernate.annotations.TenantId;
import com.orthoflow.auth.domain.model.UserRole;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Something someone has to do. Assigned to a person, or to a role so that it shows
 * for everyone holding it and the first to get to it ticks it off; with neither it
 * belongs to its creator.
 */
@Entity
@Table(name = "tasks")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Task {

    public enum Priority { LOW, NORMAL, HIGH, URGENT }

    public enum Status { OPEN, DONE, CANCELLED }

    public enum DocumentKind { INSURANCE_FORM, PRESCRIPTION }

    @Id
    private UUID id;

    @TenantId
    @Column(name = "practice_id", nullable = false, updatable = false)
    private UUID practiceId;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "assignee_id")
    private UUID assigneeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee_role")
    private UserRole assigneeRole;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Priority priority = Priority.NORMAL;

    @Column(name = "patient_id")
    private UUID patientId;

    /** The document the task is about (an insurance form to print, a prescription), if any. */
    @Enumerated(EnumType.STRING)
    @Column(name = "document_kind")
    private DocumentKind documentKind;

    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Status status = Status.OPEN;

    @Column(name = "done_at")
    private OffsetDateTime doneAt;

    @Column(name = "done_by")
    private UUID doneBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
