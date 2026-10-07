package com.orthoflow.reporting.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.export.application.service.ExportService;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.*;
import com.orthoflow.reporting.application.service.DoctorTimeService;
import com.orthoflow.reporting.application.service.GoalService;
import com.orthoflow.reporting.application.service.TaxSimulationService;
import com.orthoflow.reporting.application.service.IncomeStatementService;
import com.orthoflow.reporting.application.service.ProcedureActivityService;
import com.orthoflow.treatment.domain.model.TreatmentInvoiceStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Practice analytics. Procedure activity and doctor time are ANALYTICS_VIEW; the
 * income statement and goals show the clinic's money, so they are FINANCE_VIEW
 * to read and FINANCE_MANAGE to change (see SecurityConfig). The stock module's
 * {@code /stock/analytics} endpoints are unchanged.
 */
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class PracticeAnalyticsController {

    private final ProcedureActivityService procedures;
    private final DoctorTimeService doctorTime;
    private final IncomeStatementService incomeStatement;
    private final GoalService goals;
    private final TaxSimulationService taxes;
    private final ExportService exportService;
    private final CurrentUserProvider currentUser;

    // ── Procedure activity ──
    @GetMapping("/procedures")
    public ProcedureActivity procedures(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                        @RequestParam(required = false) List<TreatmentInvoiceStatus> status,
                                        @RequestParam(required = false) UUID practitionerId,
                                        @RequestParam(required = false) String category) {
        return procedures.activity(currentUser.requirePracticeId(), from, to, status, practitionerId, category);
    }

    @GetMapping("/procedures/export")
    public ResponseEntity<byte[]> exportProcedures(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                   @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                   @RequestParam(required = false) List<TreatmentInvoiceStatus> status,
                                                   @RequestParam(required = false) UUID practitionerId,
                                                   @RequestParam(required = false) String category,
                                                   @RequestParam(defaultValue = "xlsx") String format,
                                                   @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        ProcedureActivity activity = procedures.activity(practice, from, to, status, practitionerId, category);
        return exportService.respond(procedures.table(activity, lang), format, "activite-par-acte-" + from + "-" + to, practice, lang);
    }

    // ── Doctor time ──
    @GetMapping("/doctor-time")
    public DoctorTime doctorTime(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                 @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                 @RequestParam(required = false) UUID practitionerId,
                                 @RequestParam(required = false) Integer minMinutes,
                                 @RequestParam(required = false) Integer maxMinutes,
                                 @RequestParam(defaultValue = "fr") String lang) {
        return doctorTime.analyse(currentUser.requirePracticeId(), from, to, practitionerId, minMinutes, maxMinutes, lang);
    }

    // ── Income statement ──
    @GetMapping("/income-statement")
    public IncomeStatement incomeStatement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                           @RequestParam(defaultValue = "MONTH") Group group,
                                           @RequestParam(defaultValue = "COLLECTED") String basis,
                                           @RequestParam(defaultValue = "true") boolean includeRetrocessions,
                                           @RequestParam(defaultValue = "fr") String lang) {
        return incomeStatement.statement(currentUser.requirePracticeId(), from, to, group, basis, includeRetrocessions, lang);
    }

    @GetMapping("/income-statement/export")
    public ResponseEntity<byte[]> exportIncomeStatement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                                        @RequestParam(defaultValue = "MONTH") Group group,
                                                        @RequestParam(defaultValue = "COLLECTED") String basis,
                                                        @RequestParam(defaultValue = "true") boolean includeRetrocessions,
                                                        @RequestParam(defaultValue = "pdf") String format,
                                                        @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        IncomeStatement s = incomeStatement.statement(practice, from, to, group, basis, includeRetrocessions, lang);
        return exportService.respond(incomeStatement.table(s, lang), format, "cpc-" + from + "-" + to, practice, lang);
    }

    // ── Goals ──
    @GetMapping("/goals/suggestions")
    public GoalSuggestions goalSuggestions() {
        return goals.suggestions(currentUser.requirePracticeId());
    }

    @PostMapping("/goals/plan")
    public GoalPlan goalPlan(@RequestBody GoalInputs inputs) {
        return goals.plan(inputs);
    }

    @GetMapping("/goals/{year}")
    public GoalTracking goalTracking(@PathVariable int year) {
        return goals.tracking(currentUser.requirePracticeId(), year);
    }

    @PutMapping("/goals/{year}")
    public GoalTracking saveGoal(@PathVariable int year, @RequestBody SaveGoal body) {
        return goals.save(currentUser.requirePracticeId(), currentUser.requireUserId(), year, body.basis(), body.inputs());
    }

    // ── Income-tax simulation: the schedule is the clinic's own entry ──
    @GetMapping("/tax-schedule/{year}")
    public TaxSchedule taxSchedule(@PathVariable int year) {
        return taxes.schedule(currentUser.requirePracticeId(), year);
    }

    @PutMapping("/tax-schedule/{year}")
    public TaxSchedule saveTaxSchedule(@PathVariable int year, @RequestBody SaveTaxSchedule body) {
        return taxes.save(currentUser.requirePracticeId(), currentUser.requireUserId(), year, body);
    }

    @PostMapping("/tax-simulation")
    public TaxSimulation taxSimulation(@RequestBody TaxSimulationInput input) {
        return taxes.simulate(currentUser.requirePracticeId(), input);
    }

    public record SaveGoal(String basis, GoalInputs inputs) {
    }
}
