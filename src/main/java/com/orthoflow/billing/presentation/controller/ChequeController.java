package com.orthoflow.billing.presentation.controller;

import com.orthoflow.billing.application.service.ChequeService;
import com.orthoflow.billing.domain.model.Cheque;
import com.orthoflow.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/cheques")
@RequiredArgsConstructor
public class ChequeController {

    private final ChequeService service;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<ChequeService.View> search(@RequestParam(required = false) Cheque.Status status,
                                           @RequestParam(required = false) UUID patientId,
                                           @RequestParam(required = false) Boolean guarantee,
                                           @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueTo) {
        return service.search(currentUser.requirePracticeId(), status, patientId, guarantee, dueTo);
    }

    /** A guarantee cheque held as security. A cheque that pays something is recorded as a receipt. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ChequeService.View create(@Valid @RequestBody ChequeService.Create request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PostMapping("/{id}/deposit")
    public ChequeService.View deposit(@PathVariable UUID id, @RequestBody(required = false) Map<String, LocalDate> body) {
        return service.deposit(currentUser.requirePracticeId(), id, body == null ? null : body.get("date"));
    }

    @PostMapping("/{id}/cash")
    public ChequeService.View cash(@PathVariable UUID id, @RequestBody(required = false) Map<String, LocalDate> body) {
        return service.cash(currentUser.requirePracticeId(), id, body == null ? null : body.get("date"));
    }

    @PostMapping("/{id}/reject")
    public ChequeService.View reject(@PathVariable UUID id, @RequestBody(required = false) Map<String, String> body) {
        return service.reject(currentUser.requirePracticeId(), currentUser.requireUserId(), id, body == null ? null : body.get("reason"));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable UUID id) {
        service.release(currentUser.requirePracticeId(), id);
    }
}
