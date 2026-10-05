package com.orthoflow.tasks.application.dto;

import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.tasks.domain.model.Task;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class TaskDtos {

    private TaskDtos() {
    }

    /** Name a person or a role, not both. With neither, the task is the creator's own. */
    public record Request(@NotBlank @Size(max = 300) String title, String description, UUID assigneeId, UserRole assigneeRole,
                          LocalDate dueDate, Task.Priority priority, UUID patientId) {
    }

    public record View(UUID id, String title, String description, UUID assigneeId, String assigneeName, UserRole assigneeRole,
                       UUID createdBy, LocalDate dueDate, Task.Priority priority, UUID patientId, String patientName,
                       Task.Status status, OffsetDateTime doneAt, boolean overdue) {
    }

    /** The "my tasks" page: what is late, what is for today, what is coming, what was ticked off today. */
    public record Mine(List<View> overdue, List<View> today, List<View> upcoming, List<View> doneToday) {
    }

    public record Count(long open, long overdue, long dueToday) {
    }
}
