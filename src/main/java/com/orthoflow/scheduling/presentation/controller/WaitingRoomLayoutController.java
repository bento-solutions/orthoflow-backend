package com.orthoflow.scheduling.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.scheduling.application.dto.WaitingRoomLayoutDtos.AssignSeatRequest;
import com.orthoflow.scheduling.application.dto.WaitingRoomLayoutDtos.LayoutResponse;
import com.orthoflow.scheduling.application.dto.WaitingRoomLayoutDtos.LayoutSaveRequest;
import com.orthoflow.scheduling.application.service.WaitingRoomLayoutService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * The waiting room's plan, and who is sitting on it.
 *
 * <p>Reading is open to anyone who can see the agenda — the board is on a screen at the
 * desk. Changing the furniture or moving people needs WAITING_ROOM_MANAGE, the same
 * permission that already governs the rest of the front desk.
 */
@RestController
@RequestMapping("/waiting-room")
@RequiredArgsConstructor
public class WaitingRoomLayoutController {

    private final WaitingRoomLayoutService service;
    private final CurrentUserProvider currentUser;

    /** The plan for one room, or the practice's first room when none is named. */
    @GetMapping("/layout")
    public LayoutResponse layout(@RequestParam(required = false) UUID waitingRoomId) {
        return service.layout(currentUser.requirePracticeId(), waitingRoomId);
    }

    /** Replaces the plan wholesale. Refused when it would strand a seated patient. */
    @PutMapping("/layout")
    @PreAuthorize("hasAuthority('WAITING_ROOM_MANAGE')")
    public LayoutResponse save(@Valid @RequestBody LayoutSaveRequest request) {
        return service.saveLayout(currentUser.requirePracticeId(), request);
    }

    /** Sits a waiting patient down, or moves one already sitting to another seat. */
    @PostMapping("/seats")
    @PreAuthorize("hasAuthority('WAITING_ROOM_MANAGE')")
    public LayoutResponse assign(@Valid @RequestBody AssignSeatRequest request) {
        return service.assign(currentUser.requirePracticeId(), request);
    }

    /** Stands a patient up without otherwise touching their visit. */
    @DeleteMapping("/seats/{appointmentId}")
    @PreAuthorize("hasAuthority('WAITING_ROOM_MANAGE')")
    public LayoutResponse release(@PathVariable UUID appointmentId) {
        return service.release(currentUser.requirePracticeId(), appointmentId);
    }
}
