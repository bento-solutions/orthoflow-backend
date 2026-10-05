package com.orthoflow.tasks.application.service;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.auth.domain.repository.UserRepository;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.common.tenancy.PracticeZone;
import com.orthoflow.messaging.application.service.StaffNotifier;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import com.orthoflow.tasks.application.dto.TaskDtos.*;
import com.orthoflow.tasks.domain.model.Task;
import com.orthoflow.tasks.infrastructure.TaskJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Tasks for the team. A task is one's own if it was given to them, to their role,
 * or they made it with no one else named; one can change what is theirs, and an
 * administrator (TASKS_ADMIN) can change anyone's.
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskJpaRepository tasks;
    private final UserRepository users;
    private final PatientLookup patientLookup;
    private final StaffNotifier notifier;
    private final PracticeZone practiceZone;
    private final LiveEventPublisher liveEvents;
    private final CurrentUserProvider currentUser;

    @Transactional
    public View create(UUID practiceId, UUID actorId, Request r) {
        validate(practiceId, r);
        Task saved = tasks.save(Task.builder().practiceId(practiceId).title(r.title().trim()).description(r.description())
                .assigneeId(r.assigneeId()).assigneeRole(r.assigneeRole()).createdBy(actorId).dueDate(r.dueDate())
                .priority(r.priority() == null ? Task.Priority.NORMAL : r.priority()).patientId(r.patientId()).build());
        announce(practiceId, actorId, saved);
        liveEvents.publish(practiceId, "task", saved.getId());
        return views(practiceId, List.of(saved)).get(0);
    }

    @Transactional
    public View update(UUID practiceId, UUID actorId, UUID id, Request r) {
        Task t = requireEditable(practiceId, actorId, id);
        validate(practiceId, r);
        boolean reassigned = !Objects.equals(t.getAssigneeId(), r.assigneeId()) || t.getAssigneeRole() != r.assigneeRole();
        t.setTitle(r.title().trim());
        t.setDescription(r.description());
        t.setAssigneeId(r.assigneeId());
        t.setAssigneeRole(r.assigneeRole());
        t.setDueDate(r.dueDate());
        if (r.priority() != null) t.setPriority(r.priority());
        t.setPatientId(r.patientId());
        if (reassigned) {
            announce(practiceId, actorId, t);
        }
        liveEvents.publish(practiceId, "task", id);
        return views(practiceId, List.of(tasks.save(t))).get(0);
    }

    @Transactional
    public View done(UUID practiceId, UUID actorId, UUID id) {
        Task t = requireEditable(practiceId, actorId, id);
        if (t.getStatus() != Task.Status.OPEN) {
            throw new ConflictException("This task is " + t.getStatus());
        }
        t.setStatus(Task.Status.DONE);
        t.setDoneAt(OffsetDateTime.now());
        t.setDoneBy(actorId);
        liveEvents.publish(practiceId, "task", id);
        return views(practiceId, List.of(t)).get(0);
    }

    @Transactional
    public View reopen(UUID practiceId, UUID actorId, UUID id) {
        Task t = requireEditable(practiceId, actorId, id);
        t.setStatus(Task.Status.OPEN);
        t.setDoneAt(null);
        t.setDoneBy(null);
        liveEvents.publish(practiceId, "task", id);
        return views(practiceId, List.of(t)).get(0);
    }

    @Transactional
    public void cancel(UUID practiceId, UUID actorId, UUID id) {
        Task t = requireEditable(practiceId, actorId, id);
        t.setStatus(Task.Status.CANCELLED);
        liveEvents.publish(practiceId, "task", id);
    }

    @Transactional(readOnly = true)
    public Mine mine(UUID practiceId, UUID userId, UserRole role) {
        ZoneId zone = practiceZone.of(practiceId);
        LocalDate today = LocalDate.now(zone);
        List<View> open = views(practiceId, tasks.openFor(practiceId, userId, role));
        List<View> done = views(practiceId, tasks.doneSince(practiceId, userId, role, today.atStartOfDay(zone).toOffsetDateTime()));
        return new Mine(open.stream().filter(v -> v.overdue()).toList(),
                open.stream().filter(v -> today.equals(v.dueDate())).toList(),
                open.stream().filter(v -> !v.overdue() && !today.equals(v.dueDate())).toList(), done);
    }

    @Transactional(readOnly = true)
    public Count count(UUID practiceId, UUID userId, UserRole role) {
        LocalDate today = LocalDate.now(practiceZone.of(practiceId));
        List<Task> open = tasks.openFor(practiceId, userId, role);
        return new Count(open.size(), open.stream().filter(t -> t.getDueDate() != null && t.getDueDate().isBefore(today)).count(),
                open.stream().filter(t -> today.equals(t.getDueDate())).count());
    }

    /** Everyone's tasks: the administrator's view. */
    @Transactional(readOnly = true)
    public List<View> all(UUID practiceId, Task.Status status, UUID assigneeId, UUID patientId) {
        return views(practiceId, tasks.search(practiceId, status, assigneeId, patientId));
    }

    private void validate(UUID practiceId, Request r) {
        if (r.assigneeId() != null && r.assigneeRole() != null) {
            throw new ValidationException("Name a person or a role, not both");
        }
        if (r.assigneeId() != null) {
            users.findById(r.assigneeId()).filter(u -> u.isActive() && practiceId.equals(u.getPracticeId()))
                    .orElseThrow(() -> new NotFoundException("Assignee not found"));
        }
        if (r.patientId() != null && !patientLookup.exists(r.patientId())) {
            throw new NotFoundException("Patient not found");
        }
    }

    /** Tells whoever it was handed to, unless they handed it to themselves. */
    private void announce(UUID practiceId, UUID actorId, Task t) {
        String subject = "Nouvelle tâche";
        String body = t.getTitle() + (t.getDueDate() == null ? "" : " — pour le " + t.getDueDate());
        if (t.getAssigneeId() != null && !t.getAssigneeId().equals(actorId)) {
            notifier.toUser(practiceId, t.getAssigneeId(), MessagePurpose.TASK_ASSIGNED, subject, body, "TASK", t.getId());
        } else if (t.getAssigneeRole() != null) {
            notifier.toRole(practiceId, t.getAssigneeRole(), MessagePurpose.TASK_ASSIGNED, subject, body, "TASK", t.getId());
        }
    }

    private Task requireEditable(UUID practiceId, UUID actorId, UUID id) {
        Task t = tasks.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Task not found"));
        UserRole role = UserRole.valueOf(currentUser.requireRole());
        boolean mine = actorId.equals(t.getAssigneeId()) || role == t.getAssigneeRole() || actorId.equals(t.getCreatedBy());
        if (!mine && !currentUser.hasAuthority(Permission.TASKS_ADMIN.name())) {
            // Someone else's task is "not found", not a different error.
            throw new NotFoundException("Task not found");
        }
        return t;
    }

    private List<View> views(UUID practiceId, List<Task> rows) {
        LocalDate today = LocalDate.now(practiceZone.of(practiceId));
        Map<UUID, String> names = new HashMap<>();
        users.findAll().forEach(u -> names.put(u.getId(), u.getFirstName() + " " + u.getLastName()));
        Map<UUID, PatientSummary> patients = patientLookup.findSummaries(rows.stream().map(Task::getPatientId).filter(Objects::nonNull).distinct().toList());
        return rows.stream().map(t -> new View(t.getId(), t.getTitle(), t.getDescription(), t.getAssigneeId(), names.get(t.getAssigneeId()),
                t.getAssigneeRole(), t.getCreatedBy(), t.getDueDate(), t.getPriority(), t.getPatientId(),
                t.getPatientId() == null || patients.get(t.getPatientId()) == null ? null : patients.get(t.getPatientId()).fullName(),
                t.getStatus(), t.getDoneAt(), t.getStatus() == Task.Status.OPEN && t.getDueDate() != null && t.getDueDate().isBefore(today))).toList();
    }
}
