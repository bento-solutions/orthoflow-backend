package com.orthoflow.tasks.presentation;

import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.tasks.application.dto.TaskDtos.*;
import com.orthoflow.tasks.application.service.TaskService;
import com.orthoflow.tasks.domain.model.Task;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService service;
    private final CurrentUserProvider currentUser;

    @GetMapping("/mine")
    public Mine mine() {
        return service.mine(currentUser.requirePracticeId(), currentUser.requireUserId(), UserRole.valueOf(currentUser.requireRole()));
    }

    /** The top-bar counter. */
    @GetMapping("/count")
    public Count count() {
        return service.count(currentUser.requirePracticeId(), currentUser.requireUserId(), UserRole.valueOf(currentUser.requireRole()));
    }

    /** Everyone's tasks — TASKS_ADMIN, enforced in SecurityConfig. */
    @GetMapping
    public List<View> all(@RequestParam(required = false) Task.Status status, @RequestParam(required = false) UUID assigneeId,
                          @RequestParam(required = false) UUID patientId) {
        return service.all(currentUser.requirePracticeId(), status, assigneeId, patientId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@Valid @RequestBody Request request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PutMapping("/{id}")
    public View update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return service.update(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }

    @PostMapping("/{id}/done")
    public View done(@PathVariable UUID id) {
        return service.done(currentUser.requirePracticeId(), currentUser.requireUserId(), id);
    }

    @PostMapping("/{id}/reopen")
    public View reopen(@PathVariable UUID id) {
        return service.reopen(currentUser.requirePracticeId(), currentUser.requireUserId(), id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID id) {
        service.cancel(currentUser.requirePracticeId(), currentUser.requireUserId(), id);
    }
}
