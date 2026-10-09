package com.orthoflow.patient;

import com.orthoflow.patient.application.dto.PatientDirectoryDtos.DuplicatePair;
import com.orthoflow.patient.application.dto.PatientDirectoryDtos.Row;
import com.orthoflow.patient.infrastructure.adapter.query.PatientDirectoryQuery;
import com.orthoflow.patient.infrastructure.adapter.query.PatientDirectoryQuery.Filter;
import com.orthoflow.testsupport.PostgresTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
class PatientDirectoryQueryDbTest {

    private JdbcTemplate jdbc;
    private PatientDirectoryQuery query;
    private UUID practice;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        query = new PatientDirectoryQuery(new NamedParameterJdbcTemplate(jdbc));
        practice = PostgresTestSupport.newPractice(jdbc);
    }

    private Filter all() {
        return new Filter(practice, null, null, null, null, null, false);
    }

    @Test
    void listsAPageSortedAndCountedWithinTheClinicOnly() {
        PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2001-03-04", "0662987654", null);
        PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", "1990-01-01", "0611111111", null);
        PostgresTestSupport.patient(jdbc, practice, "Nadia", "Zniber", "2010-06-06", "0622222222", null);
        UUID other = PostgresTestSupport.newPractice(jdbc);
        PostgresTestSupport.patient(jdbc, other, "Autre", "Clinique", null, null, null);

        List<Row> page = query.page(all(), "name", false, 0, 2);

        assertThat(query.count(all())).isEqualTo(3);
        assertThat(page).extracting(Row::lastName).containsExactly("Alaoui", "Benziane");
        assertThat(query.page(all(), "name", false, 1, 2)).extracting(Row::lastName).containsExactly("Zniber");
        assertThat(query.page(all(), "name", true, 0, 1)).extracting(Row::lastName).containsExactly("Zniber");
    }

    @Test
    void searchMatchesNameCodePhoneDigitsAndCin() {
        UUID sara = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, "+212 662-987654", "AB123456");
        PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", null, "0611111111", null);

        assertThat(search("benzi")).hasSize(1);
        assertThat(search("sara ben")).hasSize(1);
        assertThat(search("0662987654")).hasSize(1);
        assertThat(search("ab123456")).hasSize(1);
        String code = jdbc.queryForObject("SELECT patient_code FROM patients WHERE id = ?", String.class, sara);
        assertThat(search(code.toLowerCase())).hasSize(1);
        assertThat(search("nobody")).isEmpty();
    }

    private List<Row> search(String term) {
        return query.page(new Filter(practice, term, null, null, null, null, false), "name", false, 0, 25);
    }

    @Test
    void aSearchTermWithWildcardsIsNotAWildcard() {
        PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);

        assertThat(search("%")).isEmpty();
        assertThat(search("_")).isEmpty();
    }

    @Test
    void progressIsTheAverageOfActiveTreatmentsAndNextAndLastVisitComeFromAppointments() {
        UUID sara = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        UUID treatment = UUID.randomUUID();
        jdbc.update("INSERT INTO treatments (id, name, code, base_price, practice_id) VALUES (?, 'T', ?, 100, ?)", treatment, "T-" + treatment, practice);
        for (int progress : new int[]{20, 60}) {
            jdbc.update("INSERT INTO patient_treatments (id, patient_id, treatment_id, teeth, status, progress, practice_id) VALUES (?, ?, ?, '11', 'ACTIVE', ?, ?)",
                    UUID.randomUUID(), sara, treatment, progress, practice);
        }
        jdbc.update("INSERT INTO patient_treatments (id, patient_id, treatment_id, teeth, status, progress, practice_id) VALUES (?, ?, ?, '12', 'CANCELLED', 100, ?)",
                UUID.randomUUID(), sara, treatment, practice);
        appointment(sara, "now() - interval '10 days'", "COMPLETED");
        appointment(sara, "now() + interval '5 days'", "SCHEDULED");
        appointment(sara, "now() + interval '2 days'", "CANCELLED");

        Row row = query.page(all(), "name", false, 0, 5).get(0);

        assertThat(row.progress()).isEqualTo(40);
        assertThat(row.lastVisit()).isBefore(OffsetDateTime.now());
        assertThat(row.nextAppointment()).isAfter(OffsetDateTime.now().plusDays(4)).isBefore(OffsetDateTime.now().plusDays(6));
    }

    private void appointment(UUID patient, String when, String status) {
        jdbc.update("INSERT INTO appointments (id, practice_id, patient_id, date_time, type, status) VALUES (?, ?, ?, " + when + ", 'x', ?)",
                UUID.randomUUID(), practice, patient, status);
    }

    @Test
    void theListShowsWhatEachPatientOwesAndCanShowOnlyDebtors() {
        UUID owes = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", null, null, null);
        UUID settled = PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", null, null, null);
        UUID user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)", user, user + "@x.ma", practice);
        for (Object[] row : new Object[][]{{owes, "1000"}, {settled, "500"}}) {
            UUID invoice = UUID.randomUUID();
            jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, currency, total, region_code, created_by) VALUES (?, ?, ?, ?, 'DRAFT', 'MAD', CAST(? AS numeric), 'MA', ?)",
                    invoice, practice, row[0], "INV-" + invoice.toString().substring(0, 8), row[1], user);
            if (row[0].equals(settled)) {
                UUID receipt = UUID.randomUUID();
                jdbc.update("INSERT INTO receipts (id, practice_id, patient_id, amount, method, receipt_date, recorded_by) VALUES (?, ?, ?, 700, 'CASH', current_date, ?)", receipt, practice, settled, user);
                jdbc.update("INSERT INTO payments (id, invoice_id, receipt_id, amount, method, payment_date, recorded_by, practice_id) VALUES (?, ?, ?, 500, 'CASH', current_date, ?, ?)", UUID.randomUUID(), invoice, receipt, user, practice);
            }
        }

        List<Row> all = query.page(all(), "balance", true, 0, 10);
        List<Row> debtors = query.page(new Filter(practice, null, null, null, null, null, false, true), "name", false, 0, 10);

        assertThat(all).extracting(Row::lastName).containsExactly("Benziane", "Alaoui");
        assertThat(all.get(0).balanceDue()).isEqualByComparingTo("1000");
        assertThat(all.get(1).balanceDue()).isEqualByComparingTo("0");
        assertThat(all.get(1).credit()).isEqualByComparingTo("200");
        assertThat(debtors).extracting(Row::lastName).containsExactly("Benziane");
        assertThat(query.count(new Filter(practice, null, null, null, null, null, false, true))).isEqualTo(1);
    }

    @Test
    void kpisCountTheClinicsPatients() {
        PostgresTestSupport.patient(jdbc, practice, "Sara", "B", "2000-01-01", null, null);
        PostgresTestSupport.patient(jdbc, practice, "Karim", "A", "1990-01-01", null, null);
        jdbc.update("UPDATE patients SET gender = 'F' WHERE first_name = 'Sara' AND practice_id = ?", practice);
        jdbc.update("UPDATE patients SET gender = 'M' WHERE first_name = 'Karim' AND practice_id = ?", practice);
        UUID old = PostgresTestSupport.patient(jdbc, practice, "Vieux", "C", null, null, null);
        jdbc.update("UPDATE patients SET created_at = now() - interval '90 days' WHERE id = ?", old);

        var kpis = query.kpis(practice, OffsetDateTime.now().minusDays(30));

        assertThat(kpis.total()).isEqualTo(3);
        assertThat(kpis.newThisMonth()).isEqualTo(2);
        assertThat(kpis.female()).isEqualTo(1);
        assertThat(kpis.male()).isEqualTo(1);
        assertThat(kpis.otherGender()).isEqualTo(1);
        assertThat(kpis.averageAge()).isBetween(30.0, 40.0);
    }

    @Test
    void duplicatesAreFoundByCinByPhoneWithASimilarNameAndByNearIdenticalNameAndBirthDate() {
        UUID a = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2001-03-04", "0662987654", "AB123456");
        UUID b = PostgresTestSupport.patient(jdbc, practice, "Sarah", "Benziane", "2001-03-04", "0662-987654", "ab123456");
        UUID c = PostgresTestSupport.patient(jdbc, practice, "Mohamed", "Idrissi", "1980-02-02", "0644444444", null);
        UUID d = PostgresTestSupport.patient(jdbc, practice, "Mohammed", "Idrissi", "1980-02-02", "0655555555", null);

        List<DuplicatePair> pairs = query.duplicates(practice, 50);

        assertThat(pairs).extracting(p -> p.reason()).contains("CIN", "NAME");
        assertThat(pairs).anySatisfy(p -> assertThat(List.of(p.first().id(), p.second().id())).containsExactlyInAnyOrder(a, b));
        assertThat(pairs).anySatisfy(p -> assertThat(List.of(p.first().id(), p.second().id())).containsExactlyInAnyOrder(c, d));
    }

    @Test
    void aFamilySharingOnePhoneIsNotFlaggedWhenTheNamesDiffer() {
        PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2001-03-04", "0662987654", null);
        PostgresTestSupport.patient(jdbc, practice, "Youssef", "Tazi", "2012-08-08", "0662987654", null);

        assertThat(query.duplicates(practice, 50)).isEmpty();
    }

    @Test
    void duplicatesOnlyNarrowsTheListToPatientsInAPair() {
        PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2001-03-04", null, "AB123456");
        PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2001-03-04", null, "ab123456");
        PostgresTestSupport.patient(jdbc, practice, "Karim", "Alaoui", "1990-01-01", null, null);

        Filter duplicatesOnly = new Filter(practice, null, null, null, null, null, true);

        assertThat(query.count(duplicatesOnly)).isEqualTo(2);
        assertThat(query.page(duplicatesOnly, "name", false, 0, 10)).extracting(Row::lastName).containsOnly("Benziane");
    }

    @Test
    void anArchivedPatientIsNeitherListedNorMatched() {
        UUID a = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2001-03-04", null, "AB123456");
        PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2001-03-04", null, "AB123456");
        jdbc.update("UPDATE patients SET deleted_at = now() WHERE id = ?", a);

        assertThat(query.count(all())).isEqualTo(1);
        assertThat(query.duplicates(practice, 50)).isEmpty();
    }
}
