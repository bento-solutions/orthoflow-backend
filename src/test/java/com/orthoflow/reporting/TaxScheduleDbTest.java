package com.orthoflow.reporting;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.reporting.application.dto.AnalyticsDtos.*;
import com.orthoflow.reporting.application.service.TaxSimulationService;
import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The schedule a clinic enters is kept per clinic and per year, and replaced as a whole when saved again. */
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class TaxScheduleDbTest {

    private JdbcTemplate jdbc;
    private TaxSimulationService taxes;
    private UUID practice;
    private UUID otherPractice;
    private UUID user;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        taxes = new TaxSimulationService(jdbc);
        practice = PostgresTestSupport.newPractice(jdbc);
        otherPractice = PostgresTestSupport.newPractice(jdbc);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN')", user, user + "@x.ma");
    }

    private static TaxBracket band(String upTo, String rate) {
        return new TaxBracket(upTo == null ? null : new BigDecimal(upTo), new BigDecimal(rate));
    }

    private SaveTaxSchedule body(String source, TaxBracket... bands) {
        return new SaveTaxSchedule(source, List.of(bands), new BigDecimal("100"), 2);
    }

    @Test
    void noScheduleMeansNotConfiguredAndNoSimulation() {
        TaxSchedule none = taxes.schedule(practice, 2026);

        assertThat(none.configured()).isFalse();
        assertThat(none.brackets()).isEmpty();
        assertThatThrownBy(() -> taxes.simulate(practice, new TaxSimulationInput(2026, new BigDecimal("1000"), 0)))
                .isInstanceOf(ValidationException.class).hasMessageContaining("No tax schedule");
    }

    @Test
    void aSavedScheduleReadsBackInOrderAndDrivesTheSimulation() {
        taxes.save(practice, user, 2026, body("Invented, for a test", band("10000", "0"), band("20000", "10"), band(null, "20")));

        TaxSchedule saved = taxes.schedule(practice, 2026);
        assertThat(saved.configured()).isTrue();
        assertThat(saved.source()).isEqualTo("Invented, for a test");
        assertThat(saved.brackets()).extracting(b -> b.ratePercent().intValue()).containsExactly(0, 10, 20);
        assertThat(saved.brackets().get(2).upTo()).isNull();
        assertThat(saved.dependentDeduction()).isEqualByComparingTo("100");
        assertThat(saved.maxDependents()).isEqualTo(2);

        TaxSimulation result = taxes.simulate(practice, new TaxSimulationInput(2026, new BigDecimal("30000"), 1));
        assertThat(result.tax()).isEqualByComparingTo("2900");
        assertThat(result.source()).isEqualTo("Invented, for a test");
    }

    @Test
    void savingAgainReplacesTheBandsRatherThanAddingToThem() {
        taxes.save(practice, user, 2026, body("first", band("10000", "0"), band(null, "10")));
        taxes.save(practice, user, 2026, body("second", band(null, "25")));

        TaxSchedule saved = taxes.schedule(practice, 2026);
        assertThat(saved.source()).isEqualTo("second");
        assertThat(saved.brackets()).hasSize(1);
        assertThat(saved.brackets().get(0).ratePercent()).isEqualByComparingTo("25");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM practice_tax_schedules WHERE practice_id = ? AND tax_year = 2026", Integer.class, practice))
                .isEqualTo(1);
    }

    @Test
    void eachClinicAndEachYearHasItsOwnSchedule() {
        taxes.save(practice, user, 2026, body("ours", band(null, "10")));

        assertThat(taxes.schedule(otherPractice, 2026).configured()).isFalse();
        assertThat(taxes.schedule(practice, 2025).configured()).isFalse();
        assertThatThrownBy(() -> taxes.simulate(otherPractice, new TaxSimulationInput(2026, new BigDecimal("1"), 0)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void anInvalidScheduleLeavesTheSavedOneUntouched() {
        taxes.save(practice, user, 2026, body("good", band(null, "10")));

        assertThatThrownBy(() -> taxes.save(practice, user, 2026, body("", band(null, "10")))).isInstanceOf(ValidationException.class);

        assertThat(taxes.schedule(practice, 2026).source()).isEqualTo("good");
    }
}
