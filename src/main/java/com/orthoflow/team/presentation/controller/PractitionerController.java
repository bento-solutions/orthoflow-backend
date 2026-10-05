package com.orthoflow.team.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.team.application.dto.PractitionerDtos.*;
import com.orthoflow.team.application.service.PractitionerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Settings → Team. Everyone reads (the agenda needs the doctors); changes need SETTINGS_MANAGE. */
@RestController
@RequestMapping("/practitioners")
@RequiredArgsConstructor
public class PractitionerController {

    private final PractitionerService service;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<Response> list(@RequestParam(defaultValue = "false") boolean includeInactive) {
        return service.list(currentUser.requirePracticeId(), includeInactive);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Response create(@Valid @RequestBody Request request) {
        return service.create(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/{id}")
    public Response update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return service.update(currentUser.requirePracticeId(), id, request);
    }

    @PutMapping("/order")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorder(@Valid @RequestBody Reorder request) {
        service.reorder(currentUser.requirePracticeId(), request.orderedIds());
    }

    @GetMapping("/unmatched-names")
    public List<Unmatched> unmatched() {
        return service.unmatchedNames();
    }

    @PostMapping("/unmatched-names/resolve")
    public Map<String, Integer> resolve(@Valid @RequestBody ResolveUnmatched request) {
        return Map.of("updated", service.resolveUnmatched(currentUser.requirePracticeId(), request));
    }
}
