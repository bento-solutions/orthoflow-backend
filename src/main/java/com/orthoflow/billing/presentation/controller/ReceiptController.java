package com.orthoflow.billing.presentation.controller;

import com.orthoflow.billing.application.dto.ReceiptDtos.*;
import com.orthoflow.billing.application.service.ReceiptService;
import com.orthoflow.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** The patient account: receive money, see the account, spend credit, void a mistake. */
@RestController
@RequiredArgsConstructor
public class ReceiptController {

    private final ReceiptService service;
    private final CurrentUserProvider currentUser;

    @PostMapping("/patients/{patientId}/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public ReceiptView record(@PathVariable UUID patientId, @Valid @RequestBody RecordReceipt request) {
        return service.record(currentUser.requirePracticeId(), currentUser.requireUserId(), patientId, request);
    }

    @GetMapping("/patients/{patientId}/account")
    public Account account(@PathVariable UUID patientId) {
        return service.account(currentUser.requirePracticeId(), patientId);
    }

    @PostMapping("/patients/{patientId}/credit/apply")
    public List<AllocationView> applyCredit(@PathVariable UUID patientId, @Valid @RequestBody ApplyCredit request) {
        return service.applyCredit(currentUser.requirePracticeId(), currentUser.requireUserId(), patientId, request);
    }

    @PostMapping("/receipts/{id}/void")
    public ReceiptView voidReceipt(@PathVariable UUID id, @Valid @RequestBody VoidReceipt request) {
        return service.voidReceipt(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request.reason(), false);
    }
}
