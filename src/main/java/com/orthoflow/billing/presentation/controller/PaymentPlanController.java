package com.orthoflow.billing.presentation.controller;

import com.orthoflow.billing.application.service.PaymentPlanService;
import com.orthoflow.common.security.CurrentUserProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
public class PaymentPlanController {

    private final PaymentPlanService service;
    private final CurrentUserProvider currentUser;

    @PostMapping("/patients/{patientId}/payment-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentPlanService.PlanView create(@PathVariable UUID patientId, @Valid @RequestBody PaymentPlanService.Create request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), patientId, request);
    }

    @GetMapping("/patients/{patientId}/payment-plans")
    public List<PaymentPlanService.PlanView> forPatient(@PathVariable UUID patientId) {
        return service.forPatient(currentUser.requirePracticeId(), patientId);
    }

    @GetMapping("/payment-plans/{id}")
    public PaymentPlanService.PlanView get(@PathVariable UUID id) {
        return service.get(currentUser.requirePracticeId(), id);
    }

    @PostMapping("/payment-plans/{id}/cancel")
    public PaymentPlanService.PlanView cancel(@PathVariable UUID id) {
        return service.cancel(currentUser.requirePracticeId(), id);
    }

    @PostMapping("/payment-plans/instalments/{instalmentId}/pay")
    public PaymentPlanService.PlanView pay(@PathVariable UUID instalmentId, @Valid @RequestBody PaymentPlanService.Pay request) {
        return service.pay(currentUser.requirePracticeId(), currentUser.requireUserId(), instalmentId, request);
    }

    /** What is due today or overdue — the receptionist's collection list. */
    @GetMapping("/payment-plans/due")
    public List<PaymentPlanService.DueView> due(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate until) {
        return service.due(currentUser.requirePracticeId(), until);
    }
}
