package com.orthoflow.retrocession;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.activity.application.service.ActivityLog;
import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.export.infrastructure.PdfService;
import com.orthoflow.retrocession.application.dto.RetrocessionDtos.*;
import com.orthoflow.retrocession.application.service.RetrocessionService;
import com.orthoflow.retrocession.application.service.StatementService;
import com.orthoflow.retrocession.domain.model.Basis;
import com.orthoflow.retrocession.domain.model.RetrocessionRule;
import com.orthoflow.retrocession.infrastructure.RetrocessionQuery;
import com.orthoflow.retrocession.infrastructure.RetrocessionRuleJpaRepository;
import com.orthoflow.retrocession.infrastructure.adapter.InvoiceAttributionGuardAdapter;
import com.orthoflow.team.application.service.PractitionerService;
import com.orthoflow.team.domain.model.Practitioner;
import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Retrocessions against a real database: the apportioning SQL, the frozen
 * statement and the triggers that keep it frozen, advance settlement, and the
 * guard that stops a paid-on invoice moving to a colleague.
 */
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class RetrocessionDbTest {

    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 31);

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID doctor;
    private UUID patient;
    private UUID user;
    private UUID invoiceA;
    private String bonding;
    private String retention;
    private RetrocessionRule rule;
    private StatementService statements;
    private InvoiceAttributionGuardAdapter guard;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN')", user, user + "@x.ma");
        doctor = UUID.randomUUID();
        jdbc.update("INSERT INTO practitioners (id, practice_id, display_name) VALUES (?, ?, 'Dr Tazi')", doctor, practice);

        String tag = UUID.randomUUID().toString().substring(0, 6);
        bonding = "BOND-" + tag;
        retention = "RET-" + tag;
        treatment(bonding, "Bonding");
        treatment(retention, "Retention");

        // Invoice A: 1000, 600 bonding + 400 retention, 500 collected on 10 March.
        invoiceA = invoice("1000", "PARTIALLY_PAID", doctor, LocalDate.of(2026, 3, 2));
        line(invoiceA, bonding, "600", 0);
        line(invoiceA, retention, "400", 1);
        allocate(invoiceA, "500", "CASH", LocalDate.of(2026, 3, 10), false);

        // Invoice B: nobody is credited with it. Invoice C: cancelled. A voided receipt on A.
        UUID b = invoice("200", "PAID", null, LocalDate.of(2026, 3, 3));
        line(b, bonding, "200", 0);
        allocate(b, "200", "CARD", LocalDate.of(2026, 3, 12), false);
        UUID c = invoice("300", "CANCELLED", doctor, LocalDate.of(2026, 3, 4));
        line(c, bonding, "300", 0);
        allocate(c, "300", "CASH", LocalDate.of(2026, 3, 13), false);
        allocate(invoiceA, "100", "CASH", LocalDate.of(2026, 3, 14), true);

        rule = RetrocessionRule.builder().id(UUID.randomUUID()).practiceId(practice).practitionerId(doctor).basis(Basis.COLLECTED)
                .ratePercent(new BigDecimal("30")).deductLabFees(false).fixedMonthlyAmount(BigDecimal.ZERO)
                .effectiveFrom(LocalDate.of(2026, 1, 1)).overrides(new java.util.HashMap<>(Map.of("Retention", new BigDecimal("50")))).build();
        statements = statementService(List.of(rule));
        guard = new InvoiceAttributionGuardAdapter(jdbc);
    }

    private StatementService statementService(List<RetrocessionRule> rules) {
        RetrocessionRuleJpaRepository repo = mock(RetrocessionRuleJpaRepository.class);
        when(repo.findOverlapping(any(), any(), any(), any())).thenReturn(rules);
        PractitionerService practitioners = mock(PractitionerService.class);
        Practitioner p = Practitioner.builder().id(doctor).displayName("Dr Tazi").build();
        when(practitioners.require(any(), any())).thenReturn(p);
        when(practitioners.byIds(any())).thenReturn(Map.of(doctor, p));
        RetrocessionService simulation = new RetrocessionService(repo, new RetrocessionQuery(new NamedParameterJdbcTemplate(jdbc)), practitioners);
        return new StatementService(jdbc, simulation, repo, practitioners, id -> ZoneId.of("UTC"), new ObjectMapper(),
                mock(LiveEventPublisher.class), mock(ActivityLog.class), mock(PdfService.class), mock(LetterheadProvider.class));
    }

    private void treatment(String code, String category) {
        jdbc.update("INSERT INTO treatments (id, name, code, base_price, category, practice_id) VALUES (?, ?, ?, 100, ?, ?)",
                UUID.randomUUID(), category + " " + code, code, category, practice);
    }

    private UUID invoice(String total, String status, UUID practitioner, LocalDate issued) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, issue_date, currency, subtotal, tax_amount, total,
                    region_code, created_by, practitioner_id)
                VALUES (?, ?, ?, ?, ?, ?, 'MAD', CAST(? AS numeric), 0, CAST(? AS numeric), 'MA', ?, ?)""",
                id, practice, patient, "INV-" + id.toString().substring(0, 8), status, issued, total, total, user, practitioner);
        return id;
    }

    private void line(UUID invoice, String actCode, String total, int order) {
        jdbc.update("INSERT INTO invoice_lines (id, invoice_id, act_code, label, quantity, unit_price, discount_pct, line_total, sort_order) VALUES (?, ?, ?, 'x', 1, CAST(? AS numeric), 0, CAST(? AS numeric), ?)",
                UUID.randomUUID(), invoice, actCode, total, total, order);
    }

    private void allocate(UUID invoice, String amount, String method, LocalDate date, boolean voidedReceipt) {
        UUID receipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, practice_id, patient_id, amount, method, receipt_date, recorded_by, voided_at) VALUES (?, ?, ?, CAST(? AS numeric), ?, ?, ?, ?)",
                receipt, practice, patient, amount, method, date, user, voidedReceipt ? java.sql.Timestamp.valueOf("2026-03-20 10:00:00") : null);
        jdbc.update("INSERT INTO payments (id, invoice_id, receipt_id, amount, method, payment_date, recorded_by) VALUES (?, ?, ?, CAST(? AS numeric), ?, ?, ?)",
                UUID.randomUUID(), invoice, receipt, amount, method, date, user);
    }

    private ValidateRequest request() {
        return new ValidateRequest(doctor, FROM, TO, null, null, null);
    }

    private UUID advance(String amount, LocalDate date) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO retrocession_advances (id, practice_id, practitioner_id, advance_date, amount) VALUES (?, ?, ?, ?, CAST(? AS numeric))",
                id, practice, doctor, date, amount);
        return id;
    }

    private BigDecimal outstanding(UUID advance) {
        return new RetrocessionQuery(new NamedParameterJdbcTemplate(jdbc)).outstandingAdvances(practice, doctor, LocalDate.of(2099, 1, 1)).stream()
                .filter(a -> a.id().equals(advance)).map(RetrocessionQuery.OutstandingAdvance::outstanding).findFirst().orElse(BigDecimal.ZERO);
    }

    @Test
    void aPaymentIsSplitAcrossTheInvoiceLinesByShareAndTheCategoryRateApplies() {
        // 500 of 1000 collected: 300 bonding at 30% = 90, 200 retention at the 50% override = 100.
        Simulation s = simulation();
        PractitionerFigures f = s.practitioners().get(0);
        assertThat(f.base()).isEqualByComparingTo("500.00");
        assertThat(f.variable()).isEqualByComparingTo("190.00");
        assertThat(f.gross()).isEqualByComparingTo("190.00");
        assertThat(f.lines()).extracting(LineView::category).containsExactlyInAnyOrder("Bonding", "Retention");
    }

    private Simulation simulation() {
        RetrocessionRuleJpaRepository repo = mock(RetrocessionRuleJpaRepository.class);
        when(repo.findOverlapping(any(), any(), any(), any())).thenReturn(List.of(rule));
        PractitionerService practitioners = mock(PractitionerService.class);
        when(practitioners.byIds(any())).thenReturn(Map.of(doctor, Practitioner.builder().id(doctor).displayName("Dr Tazi").build()));
        return new RetrocessionService(repo, new RetrocessionQuery(new NamedParameterJdbcTemplate(jdbc)), practitioners)
                .simulate(practice, null, FROM, TO, null);
    }

    @Test
    void moneyNoOneIsCreditedWithIsReportedNotPaid() {
        Unattributed u = simulation().unattributed();
        assertThat(u.invoiceCount()).isEqualTo(1);
        assertThat(u.amount()).isEqualByComparingTo("200.00");
        assertThat(u.invoices().get(0).invoiceNumber()).startsWith("INV-");
    }

    @Test
    void theMethodFilterNarrowsWhatCounts() {
        RetrocessionRuleJpaRepository repo = mock(RetrocessionRuleJpaRepository.class);
        when(repo.findOverlapping(any(), any(), any(), any())).thenReturn(List.of(rule));
        PractitionerService practitioners = mock(PractitionerService.class);
        when(practitioners.byIds(any())).thenReturn(Map.of());
        Simulation cardOnly = new RetrocessionService(repo, new RetrocessionQuery(new NamedParameterJdbcTemplate(jdbc)), practitioners)
                .simulate(practice, null, FROM, TO, new Filters(List.of(com.orthoflow.billing.domain.model.PaymentMethod.CARD), null));
        // The doctor's only payment is cash.
        assertThat(cardOnly.practitioners().get(0).gross()).isEqualByComparingTo("0");
    }

    @Test
    void validatingFreezesTheFiguresAndTheDatabaseKeepsThemFrozen() {
        StatementView v = statements.validate(practice, user, request());

        assertThat(v.number()).matches("RET-\\d{4}-\\d{5}");
        assertThat(v.gross()).isEqualByComparingTo("190.00");
        assertThat(v.net()).isEqualByComparingTo("190.00");
        assertThat(v.lines()).hasSize(2);
        assertThat(v.status()).isEqualTo(PayStatus.UNPAID);

        assertThatThrownBy(() -> jdbc.update("UPDATE retrocession_statements SET net_amount = 1 WHERE id = ?", v.id()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM retrocession_statements WHERE id = ?", v.id()))
                .hasMessageContaining("cannot be deleted");
        assertThatThrownBy(() -> jdbc.update("UPDATE retrocession_statement_lines SET amount = 1 WHERE statement_id = ?", v.id()))
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM retrocession_statement_lines WHERE statement_id = ?", v.id()))
                .hasMessageContaining("immutable");
    }

    @Test
    void aStatementIsRecomputedOnTheServerNotTakenFromTheClient() {
        // The request type carries no amounts at all; this documents that a later payment changes the next validation, not a past one.
        StatementView first = statements.validate(practice, user, request());
        statements.voidStatement(practice, user, first.id(), "recheck");
        allocate(invoiceA, "100", "CASH", LocalDate.of(2026, 3, 20), false);
        StatementView second = statements.validate(practice, user, request());
        assertThat(first.gross()).isEqualByComparingTo("190.00");
        assertThat(second.gross()).isGreaterThan(first.gross());
    }

    @Test
    void aPeriodCannotBeValidatedTwiceUntilTheFirstIsVoided() {
        StatementView first = statements.validate(practice, user, request());
        assertThatThrownBy(() -> statements.validate(practice, user, new ValidateRequest(doctor, LocalDate.of(2026, 3, 15), LocalDate.of(2026, 4, 15), null, null, null)))
                .isInstanceOf(ConflictException.class);

        StatementView voided = statements.voidStatement(practice, user, first.id(), "wrong period");
        assertThat(voided.status()).isEqualTo(PayStatus.VOID);
        assertThat(voided.voidReason()).isEqualTo("wrong period");
        assertThat(statements.validate(practice, user, request()).status()).isEqualTo(PayStatus.UNPAID);
    }

    @Test
    void anAdvanceIsSettledOldestFirstAndFreedWhenTheStatementIsVoided() {
        UUID early = advance("100", LocalDate.of(2026, 2, 1));
        UUID late = advance("150", LocalDate.of(2026, 3, 5));
        UUID future = advance("500", LocalDate.of(2026, 5, 1));

        StatementView v = statements.validate(practice, user, request());

        // 190 owed: the 100 advance is settled in full, 90 of the 150 one; the advance dated after the period is untouched.
        assertThat(v.advancesDeducted()).isEqualByComparingTo("190.00");
        assertThat(v.net()).isEqualByComparingTo("0");
        assertThat(v.status()).isEqualTo(PayStatus.PAID);
        assertThat(outstanding(early)).isEqualByComparingTo("0");
        assertThat(outstanding(late)).isEqualByComparingTo("60.00");
        assertThat(outstanding(future)).isEqualByComparingTo("500.00"); // dated after the period, so left alone

        statements.voidStatement(practice, user, v.id(), "redo");
        assertThat(outstanding(early)).isEqualByComparingTo("100.00");
        assertThat(outstanding(late)).isEqualByComparingTo("150.00");
    }

    @Test
    void payoutsCannotExceedTheNetAndBlockVoiding() {
        StatementView v = statements.validate(practice, user, request());

        StatementView part = statements.recordPayout(practice, user, v.id(), new PayoutRequest(new BigDecimal("100"), null, "CASH", null, null));
        assertThat(part.status()).isEqualTo(PayStatus.PARTIAL);
        assertThat(part.paid()).isEqualByComparingTo("100");

        assertThatThrownBy(() -> statements.recordPayout(practice, user, v.id(), new PayoutRequest(new BigDecimal("100"), null, null, null, null)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> statements.voidStatement(practice, user, v.id(), "too late"))
                .isInstanceOf(ConflictException.class);

        StatementView done = statements.recordPayout(practice, user, v.id(), new PayoutRequest(new BigDecimal("90"), null, null, null, null));
        assertThat(done.status()).isEqualTo(PayStatus.PAID);
    }

    @Test
    void anInvoiceCountedOnALiveStatementCannotMoveToAColleague() {
        guard.assertReassignable(invoiceA); // nothing counts it yet

        StatementView v = statements.validate(practice, user, request());
        assertThatThrownBy(() -> guard.assertReassignable(invoiceA)).isInstanceOf(ConflictException.class);

        statements.voidStatement(practice, user, v.id(), "redo");
        guard.assertReassignable(invoiceA);
    }

    @Test
    void labFeesReceivedInThePeriodAreNettedOffWhenTheRuleDeductsThem() {
        UUID lab = UUID.randomUUID();
        jdbc.update("INSERT INTO suppliers (id, name, kind, practice_id) VALUES (?, 'Lab Atlas', 'LAB', ?)", lab, practice);
        jdbc.update("INSERT INTO lab_orders (id, practice_id, patient_id, lab_id, practitioner_id, item_type, status, cost, received_date) VALUES (?, ?, ?, ?, ?, 'ALIGNER', 'RECEIVED', 100, ?)",
                UUID.randomUUID(), practice, patient, lab, doctor, LocalDate.of(2026, 3, 15));
        rule.setDeductLabFees(true);

        StatementView v = statementService(List.of(rule)).validate(practice, user, request());

        // 190 - 30% of the 100 lab bill
        assertThat(v.labDeduction()).isEqualByComparingTo("30.00");
        assertThat(v.gross()).isEqualByComparingTo("160.00");
        assertThat(v.lines()).extracting(LineView::kind).contains(com.orthoflow.retrocession.application.service.RetrocessionCalculator.Kind.LAB);
    }

    @Test
    void producedBasisCountsInvoicesNotPayments() {
        rule.setBasis(Basis.PRODUCED);
        Simulation s = produced();
        PractitionerFigures f = s.practitioners().get(0);
        // Invoice A is 1000 (600 bonding at 30% + 400 retention at 50%); the cancelled invoice and the unattributed one do not count.
        assertThat(f.base()).isEqualByComparingTo("1000.00");
        assertThat(f.gross()).isEqualByComparingTo("380.00");
    }

    private Simulation produced() {
        RetrocessionRuleJpaRepository repo = mock(RetrocessionRuleJpaRepository.class);
        when(repo.findOverlapping(any(), any(), any(), any())).thenReturn(List.of(rule));
        PractitionerService practitioners = mock(PractitionerService.class);
        when(practitioners.byIds(any())).thenReturn(Map.of());
        return new RetrocessionService(repo, new RetrocessionQuery(new NamedParameterJdbcTemplate(jdbc)), practitioners)
                .simulate(practice, null, FROM, TO, null);
    }
}
