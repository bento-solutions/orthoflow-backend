package com.orthoflow.tasks.infrastructure;

import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.tasks.domain.model.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TaskJpaRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByIdAndPracticeId(UUID id, UUID practiceId);

    /** Open tasks that are one's own: assigned to them, to their role, or theirs by authorship with no one else named. */
    @Query("""
            SELECT t FROM Task t
            WHERE t.practiceId = :practiceId AND t.status = com.orthoflow.tasks.domain.model.Task$Status.OPEN
              AND (t.assigneeId = :userId OR t.assigneeRole = :role
                   OR (t.assigneeId IS NULL AND t.assigneeRole IS NULL AND t.createdBy = :userId))
            ORDER BY t.dueDate ASC NULLS LAST, t.priority DESC, t.createdAt
            """)
    List<Task> openFor(@Param("practiceId") UUID practiceId, @Param("userId") UUID userId, @Param("role") UserRole role);

    @Query("""
            SELECT t FROM Task t
            WHERE t.practiceId = :practiceId AND t.status = com.orthoflow.tasks.domain.model.Task$Status.DONE AND t.doneAt >= :since
              AND (t.assigneeId = :userId OR t.assigneeRole = :role OR t.doneBy = :userId OR t.createdBy = :userId)
            ORDER BY t.doneAt DESC
            """)
    List<Task> doneSince(@Param("practiceId") UUID practiceId, @Param("userId") UUID userId, @Param("role") UserRole role,
                         @Param("since") OffsetDateTime since);

    @Query("""
            SELECT t FROM Task t
            WHERE t.practiceId = :practiceId
              AND (:status IS NULL OR t.status = :status)
              AND (:assigneeId IS NULL OR t.assigneeId = :assigneeId)
              AND (:patientId IS NULL OR t.patientId = :patientId)
            ORDER BY t.dueDate ASC NULLS LAST, t.createdAt DESC
            """)
    List<Task> search(@Param("practiceId") UUID practiceId, @Param("status") Task.Status status,
                      @Param("assigneeId") UUID assigneeId, @Param("patientId") UUID patientId);
}
