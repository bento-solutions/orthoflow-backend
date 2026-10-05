package com.orthoflow.staffmessages.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.staffmessages.application.service.StaffMessageService;
import com.orthoflow.staffmessages.application.service.StaffMessageService.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Internal messages: any signed-in member of staff, within the threads they are part of. */
@RestController
@RequestMapping("/staff-messages")
@RequiredArgsConstructor
public class StaffMessageController {

    private final StaffMessageService service;
    private final CurrentUserProvider currentUser;

    @GetMapping("/recipients")
    public List<Person> recipients() {
        return service.recipients(currentUser.requirePracticeId(), currentUser.requireUserId());
    }

    @GetMapping("/threads")
    public List<ThreadRow> threads() {
        return service.threads(currentUser.requirePracticeId(), currentUser.requireUserId());
    }

    @PostMapping("/threads")
    @ResponseStatus(HttpStatus.CREATED)
    public ThreadDetail start(@Valid @RequestBody NewThread request) {
        return service.start(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @GetMapping("/threads/{id}")
    public ThreadDetail thread(@PathVariable UUID id) {
        return service.thread(currentUser.requirePracticeId(), currentUser.requireUserId(), id);
    }

    @PostMapping("/threads/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public ThreadDetail reply(@PathVariable UUID id, @Valid @RequestBody Reply request) {
        return service.reply(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }

    @GetMapping("/unread-count")
    public Map<String, Integer> unread() {
        return Map.of("count", service.unread(currentUser.requirePracticeId(), currentUser.requireUserId()));
    }
}
