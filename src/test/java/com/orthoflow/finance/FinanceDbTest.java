package com.orthoflow.finance;

import com.orthoflow.billing.domain.model.PaymentMethod;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.finance.application.dto.FinanceDtos.*;
import com.orthoflow.finance.application.service.FinanceService;
import com.orthoflow.finance.application.service.RetrocessionFigures;
import com.orthoflow.finance.infrastructure.FinanceQuery;
import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The money read models against a real database: what was collected, what is owed,
 * what the clinic spent, and the daily cash close. Cancelled invoices and voided
 * receipts must never count.
 */
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class FinanceDbTest {

    private JdbcTemplate jdbc;
    private FinanceService service;
    private UUID practice;
    private UUID patient;
    private UUID user;
    private final LocalDate day = LocalDate.of(2026, 10, 5);

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        service = new FinanceService(new FinanceQuery(new NamedParameterJdbcTemplate(jdbc)), jdbc, id -> ZoneId.of("UTC"),
                new StaticListableBeanFactory().getBeanProvider(RetrocessionFigures.class));
        practice = PostgresTestSupport.newPractice(jdbc);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)", user, user + "@x.ma", practice);
    }

    private UUID invoice(String total, String status, LocalDate issued) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, issue_date, currency, total, region_code, created_by) VALUES (?, ?, ?, ?, ?, ?, 'MAD', CAST(? AS numeric), 'MA', ?)",
                id, practice, patient, "INV-" + id.toString().substring(0, 8), status, issued, total, user);
        return id;
    }

    private UUID receipt(String amount, String method, LocalDate date, boolean voided) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, practice_id, patient_id, amount, method, receipt_date, recorded_by, voided_at) VALUES (?, ?, ?, CAST(? AS numeric), ?, ?, ?, ?)",
                id, practice, patient, amount, method, date, user, voided ? java.sql.Timestamp.valueOf("2026-10-06 10:00:00") : null);
        return id;
    }

    private void allocate(UUID receipt, UUID invoice, String amount, LocalDate date) {
        jdbc.update("INSERT INTO payments (id, invoice_id, receipt_id, amount, method, payment_date, recorded_by, practice_id) SELECT ?, ?, id, CAST(? AS numeric), method, ?, ?, practice_id FROM receipts WHERE id = ?",
                UUID.randomUUID(), invoice, amount, date, user, receipt);
    }

    private void expense(String code, String amount, LocalDate date, String status) {
        UUID category = jdbc.queryForObject("SELECT id FROM expense_categories WHERE practice_id = '00000000-0000-0000-0000-000000000001' AND code = ?", UUID.class, code);
        jdbc.update("INSERT INTO expenses (id, practice_id, expense_date, category_id, amount, status, paid_date) VALUES (?, ?, ?, ?, CAST(? AS numeric), ?, ?)",
                UUID.randomUUID(), practice, date, category, amount, status, "PAID".equals(status) ? date : null);
    }

    @Test
    void collectionsCountOnlyLiveReceiptsAndSplitByMethodAndAdvances() {
        UUID inv = invoice("1000", "PARTIALLY_PAID", day);
        UUID cash = receipt("300", "CASH", day, false);
        allocate(cash, inv, "300", day);
        receipt("200", "CARD", day, false);
        receipt("999", "CASH", day, true);
        UUID earlier = receipt("400", "CASH", day.minusDays(3), false);
        allocate(earlier, inv, "100", day);

        CollectionsReport report = service.collections(practice, day, day, null, null);

        assertThat(report.received()).isEqualByComparingTo("500");
        assertThat(report.byMethod()).extracting(MethodTotal::method).containsExactlyInAnyOrder(PaymentMethod.CASH, PaymentMethod.CARD);
        assertThat(report.allocated()).isEqualByComparingTo("400");
        assertThat(report.advancesUsed()).isEqualByComparingTo("100");
        assertThat(report.creditAvailable()).isEqualByComparingTo("500");
        assertThat(service.collections(practice, day, day, PaymentMethod.CARD, null).rows()).hasSize(1);
    }

    @Test
    void theDebtListOwesFeesLessPaymentsAndIgnoresCancelledInvoices() {
        UUID inv = invoice("1000", "PARTIALLY_PAID", day);
        invoice("500", "CANCELLED", day);
        allocate(receipt("300", "CASH", day, false), inv, "300", day);
        UUID other = PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", null, null, null);
        UUID paidInvoice = UUID.randomUUID();
        jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, issue_date, currency, total, region_code, created_by) VALUES (?, ?, ?, ?, 'PAID', ?, 'MAD', 200, 'MA', ?)",
                paidInvoice, practice, other, "INV-" + paidInvoice.toString().substring(0, 8), day, user);
        UUID otherReceipt = UUID.randomUUID();
        jdbc.update("INSERT INTO receipts (id, practice_id, patient_id, amount, method, receipt_date, recorded_by) VALUES (?, ?, ?, 200, 'CASH', ?, ?)",
                otherReceipt, practice, other, day, user);
        allocate(otherReceipt, paidInvoice, "200", day);

        DebtSummary debts = service.debts(practice, null, null, true, null, "balance");

        assertThat(debts.rows()).hasSize(1);
        assertThat(debts.rows().get(0).lastName()).isEqualTo("Benziane");
        assertThat(debts.rows().get(0).balance()).isEqualByComparingTo("700");
        assertThat(debts.totalOwed()).isEqualByComparingTo("700");
        assertThat(service.debts(practice, null, null, false, null, "name").rows()).hasSize(2);
        assertThat(service.debts(practice, day.plusDays(1), day.plusDays(30), true, null, "balance").rows()).isEmpty();
    }

    @Test
    void aPatientWhoPaidAheadHasCreditNotDebt() {
        receipt("600", "CASH", day, false);

        DebtSummary all = service.debts(practice, null, null, false, null, "balance");

        assertThat(all.rows()).hasSize(1);
        assertThat(all.rows().get(0).credit()).isEqualByComparingTo("600");
        assertThat(all.totalOwed()).isEqualByComparingTo("0");
        assertThat(service.debts(practice, null, null, true, null, "balance").rows()).isEmpty();
    }

    @Test
    void theDashboardEquationSeparatesSalariesAndSocialChargesFromOperatingCosts() {
        UUID inv = invoice("2000", "PARTIALLY_PAID", day);
        allocate(receipt("1500", "CASH", day, false), inv, "1500", day);
        invoice("700", "CANCELLED", day);
        expense("RENT", "400", day, "PAID");
        expense("SALARIES", "300", day, "PENDING");
        expense("CNSS", "100", day, "PENDING");
        expense("SUPPLIES", "50", day, "CANCELLED");

        Dashboard d = service.dashboard(practice, day, day);

        assertThat(d.production()).isEqualByComparingTo("2000");
        assertThat(d.collections()).isEqualByComparingTo("1500");
        assertThat(d.patientDebt()).isEqualByComparingTo("500");
        assertThat(d.operatingExpenses()).isEqualByComparingTo("400");
        assertThat(d.salaries()).isEqualByComparingTo("300");
        assertThat(d.socialCharges()).isEqualByComparingTo("100");
        assertThat(d.retrocessions()).isEqualByComparingTo("0");
        assertThat(d.result()).isEqualByComparingTo("700");
        assertThat(d.expensesByCategory()).extracting(Breakdown::key).containsExactlyInAnyOrder("RENT", "SALARIES", "CNSS");
        assertThat(d.trend()).hasSize(1);
        assertThat(d.trend().get(0).collections()).isEqualByComparingTo("1500");
    }

    @Test
    void theTrendHasARowForEveryMonthEvenQuietOnes() {
        invoice("100", "DRAFT", LocalDate.of(2026, 1, 15));
        invoice("100", "DRAFT", LocalDate.of(2026, 3, 15));

        List<MonthPoint> trend = service.dashboard(practice, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31)).trend();

        assertThat(trend).extracting(MonthPoint::month).containsExactly("2026-01", "2026-02", "2026-03");
        assertThat(trend.get(1).production()).isEqualByComparingTo("0");
    }

    @Test
    void aBackwardsOrHugePeriodIsRefused() {
        assertThatThrownBy(() -> service.dashboard(practice, day, day.minusDays(1))).isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> service.dashboard(practice, day.minusYears(6), day)).isInstanceOf(ValidationException.class);
    }

    @Test
    void closingTheCashComparesCountedWithExpectedAndIsFinal() {
        receipt("300", "CASH", day, false);
        receipt("200", "CARD", day, false);
        receipt("50", "CASH", day, true);

        CashClosing preview = service.cashClosing(practice, day);
        assertThat(preview.closed()).isFalse();
        assertThat(preview.lines()).extracting(CashLine::expected).usingElementComparator(BigDecimal::compareTo)
                .containsExactlyInAnyOrder(new BigDecimal("300"), new BigDecimal("200"));

        CashClosing closed = service.close(practice, user, new CloseCash(day,
                List.of(new Counted(PaymentMethod.CASH, new BigDecimal("295")), new Counted(PaymentMethod.CARD, new BigDecimal("200"))), "écart de 5"));

        assertThat(closed.closed()).isTrue();
        assertThat(closed.totalDifference()).isEqualByComparingTo("-5");
        assertThat(closed.lines()).filteredOn(l -> l.method() == PaymentMethod.CASH).singleElement()
                .satisfies(l -> assertThat(l.difference()).isEqualByComparingTo("-5"));
        assertThatThrownBy(() -> service.close(practice, user, new CloseCash(day, List.of(new Counted(PaymentMethod.CASH, BigDecimal.ONE)), null)))
                .isInstanceOf(ConflictException.class);
    }

    @Test
    void everyMethodTakenThatDayMustBeCounted() {
        receipt("300", "CASH", day, false);
        receipt("200", "CARD", day, false);

        assertThatThrownBy(() -> service.close(practice, user, new CloseCash(day, List.of(new Counted(PaymentMethod.CASH, new BigDecimal("300"))), null)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("CARD");
    }

    @Test
    void aFutureDayCannotBeClosed() {
        assertThatThrownBy(() -> service.close(practice, user, new CloseCash(LocalDate.now().plusDays(2),
                List.of(new Counted(PaymentMethod.CASH, BigDecimal.ZERO)), null))).isInstanceOf(ValidationException.class);
    }
}
