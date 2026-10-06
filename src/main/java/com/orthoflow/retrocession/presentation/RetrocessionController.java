package com.orthoflow.retrocession.presentation;

import com.orthoflow.billing.domain.model.InvoiceStatus;
import com.orthoflow.billing.domain.model.PaymentMethod;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.export.application.service.ExportService;
import com.orthoflow.export.application.dto.ExportFormat;
import com.orthoflow.retrocession.application.dto.RetrocessionDtos.*;
import com.orthoflow.retrocession.application.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the clinic owes collaborating doctors. Reading is RETROCESSION_VIEW and
 * changing anything is RETROCESSION_MANAGE (SecurityConfig); a viewer without
 * manage rights is confined to their own figures by {@link RetrocessionAccess}.
 */
@RestController
@RequestMapping("/retrocessions")
@RequiredArgsConstructor
public class RetrocessionController {

    private final RetrocessionService service;
    private final RetrocessionRuleService ruleService;
    private final StatementService statements;
    private final RetrocessionAccess access;
    private final ExportService exportService;
    private final CurrentUserProvider currentUser;

    // ── Simulation ──
    @GetMapping("/simulation")
    public Simulation simulate(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                               @RequestParam(required = false) UUID practitionerId,
                               @RequestParam(required = false) List<PaymentMethod> method,
                               @RequestParam(required = false) List<InvoiceStatus> status) {
        return service.simulate(currentUser.requirePracticeId(), access.narrow(practitionerId), from, to, new Filters(method, status));
    }

    @GetMapping("/simulation/export")
    public ResponseEntity<byte[]> exportSimulation(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) UUID practitionerId,
                                                   @RequestParam(required = false) List<PaymentMethod> method,
                                                   @RequestParam(required = false) List<InvoiceStatus> status,
                                                   @RequestParam(defaultValue = "pdf") String format,
                                                   @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        Simulation s = service.simulate(practice, access.narrow(practitionerId), from, to, new Filters(method, status));
        return exportService.respond(RetrocessionTables.simulation(s, lang), format, "retrocessions-" + from + "-" + to, practice, lang);
    }

    // ── Rules ──
    @GetMapping("/rules")
    public List<RuleView> rules(@RequestParam(required = false) UUID practitionerId) {
        return ruleService.listRules(currentUser.requirePracticeId(), access.narrow(practitionerId));
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public RuleView createRule(@Valid @RequestBody RuleRequest request) {
        return ruleService.createRule(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PutMapping("/rules/{id}")
    public RuleView updateRule(@PathVariable UUID id, @Valid @RequestBody RuleRequest request) {
        return ruleService.updateRule(currentUser.requirePracticeId(), id, request);
    }

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRule(@PathVariable UUID id) {
        ruleService.deleteRule(currentUser.requirePracticeId(), id);
    }

    // ── Advances ──
    @GetMapping("/advances")
    public List<AdvanceView> advances(@RequestParam(required = false) UUID practitionerId) {
        return ruleService.listAdvances(currentUser.requirePracticeId(), access.narrow(practitionerId));
    }

    @PostMapping("/advances")
    @ResponseStatus(HttpStatus.CREATED)
    public AdvanceView createAdvance(@Valid @RequestBody AdvanceRequest request) {
        return ruleService.createAdvance(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @DeleteMapping("/advances/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAdvance(@PathVariable UUID id) {
        ruleService.deleteAdvance(currentUser.requirePracticeId(), id);
    }

    // ── Statements ──
    @GetMapping("/statements")
    public List<StatementSummary> statements(@RequestParam(required = false) UUID practitionerId,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                             @RequestParam(defaultValue = "false") boolean includeVoided) {
        return statements.list(currentUser.requirePracticeId(), access.narrow(practitionerId), from, to, includeVoided);
    }

    @PostMapping("/statements")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementView validate(@Valid @RequestBody ValidateRequest request) {
        return statements.validate(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @GetMapping("/statements/{id}")
    public StatementView statement(@PathVariable UUID id) {
        UUID practice = currentUser.requirePracticeId();
        access.check(statements.practitionerOf(practice, id));
        return statements.get(practice, id);
    }

    @GetMapping("/statements/{id}/pdf")
    public ResponseEntity<byte[]> statementPdf(@PathVariable UUID id, @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        access.check(statements.practitionerOf(practice, id));
        StatementView s = statements.get(practice, id);
        return exportService.download(statements.document(practice, id, lang), ExportFormat.PDF, "retrocession-" + s.number());
    }

    @PostMapping("/statements/{id}/void")
    public StatementView voidStatement(@PathVariable UUID id, @Valid @RequestBody VoidRequest request) {
        return statements.voidStatement(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request.reason());
    }

    @PostMapping("/statements/{id}/payouts")
    @ResponseStatus(HttpStatus.CREATED)
    public StatementView payout(@PathVariable UUID id, @Valid @RequestBody PayoutRequest request) {
        return statements.recordPayout(currentUser.requirePracticeId(), currentUser.requireUserId(), id, request);
    }
}
