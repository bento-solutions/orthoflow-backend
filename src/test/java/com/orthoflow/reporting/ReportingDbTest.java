package com.orthoflow.reporting;

import com.orthoflow.finance.application.service.RetrocessionFigures;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.*;
import com.orthoflow.reporting.application.service.DoctorTimeService;
import com.orthoflow.reporting.application.service.GoalService;
import com.orthoflow.reporting.application.service.IncomeStatementService;
import com.orthoflow.reporting.application.service.ProcedureActivityService;
import com.orthoflow.reporting.infrastructure.ReportingQuery;
import com.orthoflow.scheduling.application.port.OpeningHoursProvider;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.treatment.domain.model.TreatmentInvoiceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The analytics queries against a real database: procedure activity by
 * practitioner and status, doctor time with its data-quality counts, the income
 * statement's columns and goals tracking.
 */
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class ReportingDbTest {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private JdbcTemplate jdbc;
    private ReportingQuery query;
    private UUID practice;
    private UUID patient;
    private UUID user;
    private UUID drA;
    private UUID drB;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        query = new ReportingQuery(new NamedParameterJdbcTemplate(jdbc));
        practice = PostgresTestSupport.newPractice(jdbc);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN')", user, user + "@x.ma");
        drA = practitioner("Dr Tazi");
        drB = practitioner("Dr Alaoui");
    }

    private UUID practitioner(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO practitioners (id, practice_id, display_name) VALUES (?, ?, ?)", id, practice, name);
        return id;
    }

    private UUID treatment(String category, String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO treatments (id, name, code, base_price, category, practice_id) VALUES (?, ?, ?, CAST(? AS numeric), ?, ?)",
                id, category + " visit", "T-" + id.toString().substring(0, 8), price, category, practice);
        return id;
    }

    private UUID billingInvoice(UUID practitioner, String total, LocalDate issued) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, issue_date, currency, subtotal, tax_amount, total, region_code, created_by, practitioner_id)
                VALUES (?, ?, ?, ?, 'PAID', ?, 'MAD', CAST(? AS numeric), 0, CAST(? AS numeric), 'MA', ?, ?)""",
                id, practice, patient, "INV-" + id.toString().substring(0, 8), issued, total, total, user, practitioner);
        return id;
    }

    private void treatmentInvoice(UUID treatment, UUID billing, String status, String price, String material, LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO treatment_invoices (id, practice_id, invoice_number, patient_id, treatment_id, session_date, status, treatment_price,
                    consumables_cost, subtotal, total, created_by, billing_invoice_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric), CAST(? AS numeric), ?, ?)""",
                id, practice, "TI-" + id.toString().substring(0, 8), patient, treatment, date, status, price, material, price, price, user, billing);
    }

    // ── Procedure activity ──
    @Test
    void procedureActivityGroupsByPractitionerCategoryAndStatus() {
        UUID bonding = treatment("Bonding", "500");
        UUID retention = treatment("Retention", "300");
        LocalDate day = LocalDate.of(2026, 3, 10);
        treatmentInvoice(bonding, billingInvoice(drA, "500", day), "FINALIZED", "500", "50", day);
        treatmentInvoice(bonding, billingInvoice(drA, "500", day), "FINALIZED", "500", "50", day);
        treatmentInvoice(retention, billingInvoice(drB, "300", day), "FINALIZED", "300", "20", day);
        treatmentInvoice(retention, null, "FINALIZED", "300", "20", day);
        treatmentInvoice(bonding, billingInvoice(drA, "500", day), "DRAFT", "500", "50", day);
        treatmentInvoice(bonding, billingInvoice(drA, "500", day), "FINALIZED", "500", "50", day.plusMonths(2));

        ProcedureActivityService service = new ProcedureActivityService(query);
        ProcedureActivity all = service.activity(practice, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null, null);

        // FINALIZED only by default; the draft and the May session are out.
        assertThat(all.total().sessions()).isEqualTo(4);
        assertThat(all.total().revenue()).isEqualByComparingTo("1600.00");
        assertThat(all.total().materialCost()).isEqualByComparingTo("140.00");
        assertThat(all.byCategory()).extracting(GroupTotal::key).containsExactly("Bonding", "Retention");
        assertThat(all.byPractitioner()).extracting(GroupTotal::label).containsExactlyInAnyOrder("Dr Tazi", "Dr Alaoui", "—");

        ProcedureRow tazi = all.rows().stream().filter(r -> "Dr Tazi".equals(r.practitionerName())).findFirst().orElseThrow();
        assertThat(tazi.sessions()).isEqualTo(2);
        assertThat(tazi.grossMargin()).isEqualByComparingTo("900.00");
        assertThat(tazi.marginPercent()).isEqualByComparingTo("90.00");

        assertThat(service.activity(practice, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, drB, null).total().sessions()).isEqualTo(1);
        assertThat(service.activity(practice, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), null, null, "Retention").total().sessions()).isEqualTo(2);
        assertThat(service.activity(practice, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), List.of(TreatmentInvoiceStatus.DRAFT), null, null)
                .total().sessions()).isEqualTo(1);
    }

    // ── Doctor time ──
    private void visit(UUID practitioner, int hourOfDay, String status, String arrived, String seated, String finished) {
        LocalDate day = LocalDate.of(2026, 3, 10);
        String base = day + " ";
        jdbc.update("""
                INSERT INTO appointments (id, patient_id, date_time, type, status, duration_minutes, practice_id, practitioner_id, arrived_at, seated_at, finished_at)
                VALUES (?, ?, CAST(? AS timestamptz), 'ADJUSTMENT', ?, 30, ?, ?, CAST(? AS timestamptz), CAST(? AS timestamptz), CAST(? AS timestamptz))""",
                UUID.randomUUID(), patient, base + String.format("%02d:00:00+00", hourOfDay), status, practice, practitioner,
                arrived == null ? null : base + arrived + "+00", seated == null ? null : base + seated + "+00", finished == null ? null : base + finished + "+00");
    }

    @Test
    void doctorTimeAveragesPlausibleVisitsAndCountsTheRest() {
        visit(drA, 8, "COMPLETED", "07:50:00", "08:00:00", "08:30:00");   // 30 min, waited 10
        visit(drA, 9, "COMPLETED", "08:55:00", "09:00:00", "09:40:00");   // 40 min, waited 5
        visit(drA, 10, "COMPLETED", null, "10:00:00", "10:02:00");        // 2 min: too short
        visit(drA, 11, "COMPLETED", null, "11:00:00", "16:00:00");        // 300 min: too long
        visit(drA, 12, "COMPLETED", null, "12:00:00", null);              // never ended, in the past
        visit(drA, 13, "COMPLETED", null, null, "13:30:00");              // no start
        visit(drA, 14, "COMPLETED", null, "14:30:00", "14:00:00");        // ends before it starts
        visit(drA, 15, "COMPLETED", null, null, null);                    // never stamped
        visit(drA, 16, "CANCELLED", null, "16:00:00", "16:30:00");        // does not count at all

        DoctorTime t = new DoctorTimeService(query, id -> UTC).analyse(practice, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10), null, null, null, "fr");

        TimeRow row = t.byPractitioner().get(0);
        assertThat(row.practitionerName()).isEqualTo("Dr Tazi");
        assertThat(row.appointments()).isEqualTo(2);
        assertThat(row.averageMinutes()).isEqualByComparingTo("35.0");
        assertThat(row.medianMinutes()).isEqualByComparingTo("35.0");
        assertThat(row.activeMinutes()).isEqualByComparingTo("70.0");
        assertThat(row.plannedAverageMinutes()).isEqualByComparingTo("30.0");
        assertThat(row.averageWaitMinutes()).isEqualByComparingTo("7.5");

        DataQuality q = t.quality();
        assertThat(q.valid()).isEqualTo(2);
        assertThat(q.tooShort()).isEqualTo(1);
        assertThat(q.tooLong()).isEqualTo(1);
        assertThat(q.missingEnd()).isEqualTo(1);
        assertThat(q.missingStart()).isEqualTo(1);
        assertThat(q.invalidOrder()).isEqualTo(1);
        assertThat(q.completedWithoutTimes()).isEqualTo(1);
        assertThat(q.considered()).isEqualTo(7);
        assertThat(q.issues()).extracting(TimeIssue::problem)
                .containsExactlyInAnyOrder("TOO_SHORT", "TOO_LONG", "MISSING_END", "MISSING_START", "END_BEFORE_START");
    }

    @Test
    void theMedianIgnoresOutliersTheAverageWouldNot() {
        visit(drA, 8, "COMPLETED", null, "08:00:00", "08:20:00");
        visit(drA, 9, "COMPLETED", null, "09:00:00", "09:20:00");
        visit(drA, 10, "COMPLETED", null, "10:00:00", "12:00:00");        // 120 min, still plausible

        TimeRow row = new DoctorTimeService(query, id -> UTC).analyse(practice, LocalDate.of(2026, 3, 10), LocalDate.of(2026, 3, 10), drA, null, null, "fr")
                .byPractitioner().get(0);

        assertThat(row.medianMinutes()).isEqualByComparingTo("20.0");
        assertThat(row.averageMinutes()).isGreaterThan(row.medianMinutes());
    }

    // ── Income statement ──
    private void receipt(String amount, LocalDate date, boolean voided) {
        jdbc.update("INSERT INTO receipts (id, practice_id, patient_id, amount, method, receipt_date, recorded_by, voided_at) VALUES (?, ?, ?, CAST(? AS numeric), 'CASH', ?, ?, ?)",
                UUID.randomUUID(), practice, patient, amount, date, user, voided ? java.sql.Timestamp.valueOf("2026-03-30 10:00:00") : null);
    }

    private void expense(String code, String amount, LocalDate date, String status) {
        UUID category = jdbc.queryForObject("SELECT id FROM expense_categories WHERE practice_id = '00000000-0000-0000-0000-000000000001' AND code = ?", UUID.class, code);
        jdbc.update("INSERT INTO expenses (id, practice_id, expense_date, category_id, amount, status, paid_date) VALUES (?, ?, ?, ?, CAST(? AS numeric), ?, ?)",
                UUID.randomUUID(), practice, date, category, amount, status, "PAID".equals(status) ? date : null);
    }

    private IncomeStatementService incomeStatement() {
        RetrocessionFigures figures = new RetrocessionFigures() {
            @Override
            public BigDecimal owedFor(UUID practiceId, LocalDate from, LocalDate to) {
                return new BigDecimal("50");
            }

            @Override
            public Map<LocalDate, BigDecimal> accruedByDay(UUID practiceId, LocalDate from, LocalDate to) {
                return Map.of(LocalDate.of(2026, 3, 20), new BigDecimal("50"));
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("figures", figures);
        return new IncomeStatementService(query, beans.getBeanProvider(RetrocessionFigures.class));
    }

    private static StatementRow row(IncomeStatement s, String code) {
        return s.rows().stream().filter(r -> r.code().equals(code)).findFirst().orElseThrow();
    }

    @Test
    void theIncomeStatementBalancesFeesAgainstExpensesAndRetrocessions() {
        receipt("1000", LocalDate.of(2026, 3, 5), false);
        receipt("500", LocalDate.of(2026, 3, 25), false);
        receipt("9999", LocalDate.of(2026, 3, 6), true);                 // voided: never counts
        expense("RENT", "300", LocalDate.of(2026, 3, 1), "PENDING");
        expense("LAB_FEES", "100", LocalDate.of(2026, 3, 12), "PAID");
        expense("SALARIES", "200", LocalDate.of(2026, 3, 28), "PENDING");
        expense("RENT", "999", LocalDate.of(2026, 3, 2), "CANCELLED");   // cancelled: never counts
        expense("RENT", "300", LocalDate.of(2026, 4, 1), "PENDING");     // another month

        IncomeStatement s = incomeStatement().statement(practice, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), Group.MONTH, "COLLECTED", true, "fr");

        assertThat(s.periods()).hasSize(1);
        assertThat(row(s, "fees").total()).isEqualByComparingTo("1500.00");
        assertThat(row(s, "PURCHASES").total()).isEqualByComparingTo("100.00");
        assertThat(row(s, "EXTERNAL").total()).isEqualByComparingTo("350.00");     // rent 300 + retrocessions 50
        assertThat(row(s, "RETROCESSIONS").total()).isEqualByComparingTo("50.00");
        assertThat(row(s, "PERSONNEL").total()).isEqualByComparingTo("200.00");
        assertThat(row(s, "II.total").total()).isEqualByComparingTo("650.00");
        assertThat(row(s, "III").total()).isEqualByComparingTo("850.00");

        IncomeStatement without = incomeStatement().statement(practice, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), Group.MONTH, "COLLECTED", false, "fr");
        assertThat(without.rows()).noneMatch(r -> r.code().equals("RETROCESSIONS"));
        assertThat(row(without, "III").total()).isEqualByComparingTo("900.00");
    }

    @Test
    void groupingByWeekPutsEachAmountInItsOwnColumn() {
        receipt("100", LocalDate.of(2026, 3, 3), false);   // week of 2 March
        receipt("200", LocalDate.of(2026, 3, 10), false);  // week of 9 March
        receipt("400", LocalDate.of(2026, 3, 11), false);

        IncomeStatement s = incomeStatement().statement(practice, LocalDate.of(2026, 3, 2), LocalDate.of(2026, 3, 15), Group.WEEK, "COLLECTED", true, "fr");

        assertThat(s.periods()).hasSize(2);
        assertThat(row(s, "fees").amounts()).extracting(BigDecimal::intValue).containsExactly(100, 600);
        // The 50 of retrocession accrued on 20 March is outside this range entirely.
        assertThat(row(s, "RETROCESSIONS").total()).isEqualByComparingTo("0");
    }

    @Test
    void theProducedBasisCountsInvoicesNotReceipts() {
        billingInvoice(drA, "800", LocalDate.of(2026, 3, 4));
        receipt("100", LocalDate.of(2026, 3, 5), false);

        IncomeStatement s = incomeStatement().statement(practice, LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), Group.MONTH, "PRODUCED", false, "fr");

        assertThat(row(s, "fees").total()).isEqualByComparingTo("800.00");
        assertThat(s.basis()).isEqualTo("PRODUCED");
    }

    // ── Goals ──
    private GoalService goals() {
        OpeningHoursProvider hours = (practiceId, weekday) -> weekday <= 6
                ? Optional.of(new OpeningHoursProvider.Day(LocalTime.of(8, 0), LocalTime.of(18, 0), null, null)) : Optional.empty();
        return new GoalService(query, jdbc, id -> UTC, hours);
    }

    @Test
    void aSavedGoalIsTrackedMonthByMonthAndCanBeChanged() {
        receipt("20000", LocalDate.of(2025, 1, 15), false);
        receipt("50000", LocalDate.of(2025, 2, 15), false);
        receipt("5000", LocalDate.of(2025, 2, 16), true);                // voided
        GoalInputs inputs = new GoalInputs(new BigDecimal("20000"), new BigDecimal("15000"), new BigDecimal("20"), new BigDecimal("22"));

        GoalService goals = goals();
        assertThat(goals.tracking(practice, 2025).saved()).isFalse();

        GoalTracking t = goals.save(practice, user, 2025, "COLLECTED", inputs);

        assertThat(t.saved()).isTrue();
        assertThat(t.monthlyTarget()).isEqualByComparingTo("43750.00");
        assertThat(t.months()).hasSize(12);
        assertThat(t.months().get(0).actual()).isEqualByComparingTo("20000");
        assertThat(t.months().get(0).progressPercent()).isEqualByComparingTo("45.71");
        assertThat(t.months().get(0).variance()).isEqualByComparingTo("-23750");
        assertThat(t.months().get(1).actual()).isEqualByComparingTo("50000");
        assertThat(t.yearActual()).isEqualByComparingTo("70000");
        assertThat(t.yearTarget()).isEqualByComparingTo("525000");
        // A year that is over counts in full; every month projects to what it did.
        assertThat(t.yearToDateTarget()).isEqualByComparingTo("525000");
        assertThat(t.months().get(1).projected()).isEqualByComparingTo("50000");

        GoalTracking changed = goals.save(practice, user, 2025, "COLLECTED",
                new GoalInputs(new BigDecimal("10000"), new BigDecimal("10000"), BigDecimal.ZERO, new BigDecimal("20")));
        assertThat(changed.monthlyTarget()).isEqualByComparingTo("20000.00");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM practice_goals WHERE practice_id = ?", Integer.class, practice)).isEqualTo(1);
    }

    @Test
    void aFutureYearHasNoYearToDateAndNoProjection() {
        GoalTracking t = goals().save(practice, user, 2099, "COLLECTED",
                new GoalInputs(new BigDecimal("1000"), new BigDecimal("1000"), BigDecimal.ZERO, new BigDecimal("20")));
        assertThat(t.yearToDateTarget()).isEqualByComparingTo("0");
        assertThat(t.months()).allSatisfy(m -> assertThat(m.projected()).isNull());
    }

    @Test
    void theWizardSuggestsStartingValuesFromTheLastThreeFullMonths() {
        LocalDate today = LocalDate.now(UTC);
        LocalDate lastMonth = today.withDayOfMonth(1).minusMonths(1);
        expense("RENT", "3000", lastMonth, "PENDING");
        expense("SALARIES", "3000", lastMonth.minusMonths(1), "PENDING");
        expense("LAB_FEES", "600", lastMonth, "PENDING");
        receipt("6000", lastMonth, false);

        GoalSuggestions s = goals().suggestions(practice);

        assertThat(s.fixedCosts()).isEqualByComparingTo("2000.00");          // (3000 + 3000) / 3 months
        assertThat(s.variableCostPercent()).isEqualByComparingTo("10.00");    // 600 of 6000
        assertThat(s.workingDaysPerMonth()).isEqualByComparingTo("26.0");     // six open days a week
    }
}
