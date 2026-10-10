package com.orthoflow.clinical;

import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What V62 promises: one lesion per tooth surface, not per finding code, and
 * gum assessments that cannot contradict themselves.
 */
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class ChartSurfacesDbTest {

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID patient;
    private UUID user;
    private UUID chart;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)",
                user, user + "@x.ma", practice);
        chart = UUID.randomUUID();
        jdbc.update("INSERT INTO dental_charts (id, patient_id, chart_type, practice_id) VALUES (?, ?, 'adult', ?)", chart, patient, practice);
    }

    private void finding(String fdi, String code, String surface) {
        jdbc.update("""
                INSERT INTO tooth_findings (id, practice_id, chart_id, fdi, finding_code, kind, surface, recorded_by)
                VALUES (?, ?, ?, ?, ?, 'CONDITION', ?, ?)
                """, UUID.randomUUID(), practice, chart, fdi, code, surface, user);
    }

    @Test
    void theSameFindingOnDifferentSurfacesCoexists() {
        finding("16", "caries", "mesial");
        finding("16", "caries", "distal");
        finding("16", "caries", null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM tooth_findings WHERE chart_id = ?", Integer.class, chart))
                .isEqualTo(3);
    }

    @Test
    void theSameFindingOnTheSameSurfaceIsADuplicate() {
        finding("16", "caries", "mesial");
        assertThatThrownBy(() -> finding("16", "caries", "mesial"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aWholeToothFindingCanOnlyBeRecordedOnce() {
        finding("16", "caries", null);
        assertThatThrownBy(() -> finding("16", "caries", null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aResolvedFindingDoesNotBlockANewOneOnTheSameSurface() {
        finding("16", "caries", "mesial");
        jdbc.update("UPDATE tooth_findings SET status = 'RESOLVED' WHERE chart_id = ?", chart);
        finding("16", "caries", "mesial");
    }

    @Test
    void workIsThisClinicsAndOfUnknownDateByDefault() {
        finding("26", "existing_amalgam", "occlusal");
        var row = jdbc.queryForMap("SELECT origin, performed_on, provider_name FROM tooth_findings WHERE chart_id = ?", chart);

        assertThat(row.get("origin")).isEqualTo("THIS_CLINIC");
        assertThat(row.get("performed_on")).isNull();
        assertThat(row.get("provider_name")).isNull();
    }

    @Test
    void aFindingsOriginIsOneOfTwoValues() {
        finding("26", "existing_amalgam", "occlusal");
        assertThatThrownBy(() -> jdbc.update("UPDATE tooth_findings SET origin = 'ELSEWHERE' WHERE chart_id = ?", chart))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void perio(String region, String condition, Integer stage) {
        jdbc.update("""
                INSERT INTO periodontal_assessments (id, practice_id, patient_id, region, condition, stage, assessed_on, recorded_by)
                VALUES (?, ?, ?, ?, ?, ?, CURRENT_DATE, ?)
                """, UUID.randomUUID(), practice, patient, region, condition, stage, user);
    }

    @Test
    void gumAssessmentsAreAppendOnlyHistoryPerRegion() {
        perio("UPPER_FRONT", "GINGIVITIS", null);
        perio("UPPER_FRONT", "HEALTHY", null);
        perio("LOWER_LEFT", "PERIODONTITIS", 2);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM periodontal_assessments WHERE patient_id = ?", Integer.class, patient))
                .isEqualTo(3);
    }

    @Test
    void aStageWithoutPeriodontitisOrOutOfRangeIsRefused() {
        assertThatThrownBy(() -> perio("WHOLE_MOUTH", "GINGIVITIS", 2)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> perio("WHOLE_MOUTH", "PERIODONTITIS", 5)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> perio("MOUTH", "HEALTHY", null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> perio("WHOLE_MOUTH", "SICK", null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void erasingThePatientTakesTheirGumHistoryWithIt() {
        perio("WHOLE_MOUTH", "HEALTHY", null);
        jdbc.update("DELETE FROM patients WHERE id = ?", patient);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM periodontal_assessments WHERE patient_id = ?", Integer.class, patient))
                .isZero();
    }
}
