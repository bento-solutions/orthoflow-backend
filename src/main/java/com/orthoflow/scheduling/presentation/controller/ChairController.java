package com.orthoflow.scheduling.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.scheduling.application.dto.AgendaDtos.ChairDetail;
import com.orthoflow.scheduling.application.dto.AgendaDtos.ChairRequest;
import com.orthoflow.scheduling.application.dto.ChairResponse;
import com.orthoflow.scheduling.application.service.AgendaConfigService;
import com.orthoflow.scheduling.infrastructure.adapter.persistence.ChairJpaRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** Treatment rooms. The plain list is what the agenda reads; the rest is Settings → Rooms. */
@RestController
@RequestMapping("/scheduling/chairs")
@RequiredArgsConstructor
public class ChairController {

    private final ChairJpaRepository chairJpaRepository;
    private final AgendaConfigService config;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<ChairResponse> getActiveChairs() {
        return chairJpaRepository.findByPracticeIdAndActiveTrueOrderByDisplayOrderAscNameAsc(currentUser.requirePracticeId()).stream()
                .map(ChairResponse::from)
                .collect(Collectors.toList());
    }

    @GetMapping("/all")
    public List<ChairDetail> all() {
        return config.chairs(currentUser.requirePracticeId(), true);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChairDetail create(@Valid @RequestBody ChairRequest request) {
        return config.createChair(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/{id}")
    public ChairDetail update(@PathVariable UUID id, @Valid @RequestBody ChairRequest request) {
        return config.updateChair(currentUser.requirePracticeId(), id, request);
    }
}
