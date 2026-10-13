package com.orthoflow.audit;

import com.orthoflow.audit.application.service.AuditRetentionJob;
import com.orthoflow.auth.application.dto.LoginRequest;
import com.orthoflow.auth.application.service.AuthService;
import com.orthoflow.auth.infrastructure.security.JwtService;
import com.orthoflow.common.exception.UnauthorizedException;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The audit trail (V65): what is recorded, that it survives a refused action, and that nobody can rewrite it. */
@AutoConfigureMockMvc
class AuditTrailTest extends SpringDbTest {

    @Autowired
    private AuthService auth;
    @Autowired
    private PasswordEncoder encoder;
    @Autowired
    private AuditRetentionJob retention;
    @Autowired
    private JwtService jwt;
    @Autowired
    private MockMvc mockMvc;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID admin;
    private String email;

    @BeforeEach
    void aClinicWithAnAdmin() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        admin = UUID.randomUUID();
        email = admin + "@x.ma";
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, ?, 'A', 'B', 'ADMIN', ?)",
                admin, email, encoder.encode("correct horse battery"), practice);
    }

    private List<Map<String, Object>> rows(String where, Object... args) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE " + where + " ORDER BY occurred_at", args);
    }

    @Test
    void aSignInAndAFailedSignInAreBothRecordedAndTheFailureSurvivesTheRollback() {
        auth.login(login(email, "correct horse battery"), "10.0.0.1", "JUnit");
        assertThatThrownBy(() -> auth.login(login(email, "wrong"), "10.0.0.2", "JUnit")).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> auth.login(login("nobody-" + admin + "@x.ma", "x"), "10.0.0.3", "JUnit")).isInstanceOf(UnauthorizedException.class);

        assertThat(rows("practice_id = ? AND category = 'AUTH'", practice)).extracting(r -> r.get("action")).containsExactly("LOGIN", "LOGIN_FAILED");
        Map<String, Object> failed = rows("practice_id = ? AND action = 'LOGIN_FAILED'", practice).get(0);
        assertThat(failed.get("ip")).isEqualTo("10.0.0.2");
        assertThat((String) failed.get("detail")).contains("wrong_password").doesNotContain("wrong\"");
        // An address that is no account still leaves a trace, with no clinic.
        assertThat(rows("practice_id IS NULL AND actor_label = ?", "nobody-" + admin + "@x.ma")).hasSize(1);
    }

    @Test
    void thePasswordIsNeverWrittenToTheLog() {
        assertThatThrownBy(() -> auth.login(login(email, "super-secret-attempt"), "10.0.0.2", "JUnit")).isInstanceOf(UnauthorizedException.class);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE detail LIKE '%super-secret-attempt%' OR actor_label LIKE '%super-secret-attempt%'",
                Integer.class)).isZero();
    }

    @Test
    void theLogCannotBeRewrittenOrEmptied() {
        auth.login(login(email, "correct horse battery"), "10.0.0.1", "JUnit");

        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'NOTHING' WHERE practice_id = ?", practice)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE practice_id = ?", practice)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE audit_log")).isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(rows("practice_id = ?", practice)).isNotEmpty();
    }

    @Test
    void retentionRemovesOldRowsAndReadsGoSoonerThanTheRest() {
        UUID patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2010-01-01", "0611223344", null);
        // Backdate through the one door the trigger leaves open for the retention job's own transaction.
        insertOld("PHI_READ", 400, patient);
        insertOld("PHI_READ", 10, patient);
        insertOld("AUTH", 400, null);
        insertOld("AUTH", 1200, null);

        int purged = retention.purge();

        // 400-day-old read (reads keep 365 days) and 1200-day-old sign-in (everything else keeps 1095); the rest stays.
        assertThat(purged).isEqualTo(2);
        assertThat(rows("practice_id = ? AND category = 'PHI_READ'", practice)).hasSize(1);
        assertThat(rows("practice_id = ? AND category = 'AUTH'", practice)).hasSize(1);
    }

    private void insertOld(String category, int daysAgo, UUID patient) {
        jdbc.update("INSERT INTO audit_log (id, occurred_at, practice_id, category, action, patient_id) VALUES (?, now() - make_interval(days => ?), ?, ?, 'TEST', ?)",
                UUID.randomUUID(), daysAgo, practice, category, patient);
    }

    @Test
    void readingAPatientOverHttpIsRecordedWithTheRouteTemplateAndNoQueryString() throws Exception {
        UUID patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2010-01-01", "0611223344", null);
        String bearer = "Bearer " + jwt.generateToken(admin, email, "ADMIN");

        mockMvc.perform(get("/patients/" + patient).header("Authorization", bearer)).andExpect(status().isOk());

        List<Map<String, Object>> reads = rows("practice_id = ? AND category = 'PHI_READ'", practice);
        assertThat(reads).hasSize(1);
        assertThat(reads.get(0)).containsEntry("route", "/patients/{id}").containsEntry("method", "GET").containsEntry("status", 200)
                .containsEntry("actor_id", admin).containsEntry("patient_id", patient);
    }

    @Test
    void aRefusedAccessIsRecordedToo() throws Exception {
        UUID patientOfAnotherClinic = PostgresTestSupport.patient(jdbc, PostgresTestSupport.newPractice(jdbc), "X", "Y", "2010-01-01", "0600000000", null);
        String bearer = "Bearer " + jwt.generateToken(admin, email, "ADMIN");

        mockMvc.perform(get("/patients/" + patientOfAnotherClinic).header("Authorization", bearer)).andExpect(status().isNotFound());

        assertThat(rows("practice_id = ? AND category = 'PHI_READ'", practice)).singleElement().satisfies(r -> assertThat(r.get("status")).isEqualTo(404));
    }

    @Test
    void anAdministratorSeesOnlyTheirOwnClinicsTrail() throws Exception {
        UUID otherClinic = PostgresTestSupport.newPractice(jdbc);
        UUID otherAdmin = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)",
                otherAdmin, otherAdmin + "@x.ma", otherClinic);
        auth.login(login(email, "correct horse battery"), "10.0.0.1", "JUnit");
        String otherBearer = "Bearer " + jwt.generateToken(otherAdmin, otherAdmin + "@x.ma", "ADMIN");
        String bearer = "Bearer " + jwt.generateToken(admin, email, "ADMIN");

        String mine = mockMvc.perform(get("/admin/audit-log").param("category", "AUTH").header("Authorization", bearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String theirs = mockMvc.perform(get("/admin/audit-log").param("category", "AUTH").header("Authorization", otherBearer))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        assertThat(mine).contains("\"LOGIN\"").contains(email);
        assertThat(theirs).doesNotContain(email).contains("\"total\":0");
    }

    @Test
    void aClinicSettingChangedOverHttpIsRecordedAsAnAdminChange() throws Exception {
        String bearer = "Bearer " + jwt.generateToken(admin, email, "ADMIN");

        // The body is invalid on purpose: the attempt is recorded whatever became of it.
        mockMvc.perform(put("/settings/practice").header("Authorization", bearer).contentType("application/json").content("{}"));

        assertThat(rows("practice_id = ? AND category = 'ADMIN'", practice)).anySatisfy(r -> assertThat(r).containsEntry("route", "/settings/practice")
                .containsEntry("method", "PUT"));
    }

    @Test
    void exportAnonymisationAndErasureOfAPatientAreEachRecordedAndTheTraceOutlivesThePatient() throws Exception {
        String bearer = "Bearer " + jwt.generateToken(admin, email, "ADMIN");
        UUID exported = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "2010-01-01", "0611223344", null);
        UUID anonymised = PostgresTestSupport.patient(jdbc, practice, "Omar", "Idrissi", "2011-01-01", "0622334455", null);
        UUID erased = PostgresTestSupport.patient(jdbc, practice, "Lina", "Tazi", "2012-01-01", "0633445566", null);

        mockMvc.perform(get("/patients/" + exported + "/compliance/export").header("Authorization", bearer)).andExpect(status().isOk());
        mockMvc.perform(post("/patients/" + anonymised + "/compliance/anonymise").header("Authorization", bearer)).andExpect(status().isNoContent());
        mockMvc.perform(delete("/patients/" + erased + "/erase").header("Authorization", bearer)).andExpect(status().isNoContent());

        assertThat(rows("practice_id = ? AND category = 'DATA_RIGHTS'", practice)).extracting(r -> r.get("action"), r -> r.get("patient_id"))
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("PATIENT_EXPORTED", exported),
                        org.assertj.core.groups.Tuple.tuple("PATIENT_ANONYMISED", anonymised),
                        org.assertj.core.groups.Tuple.tuple("PATIENT_ERASED", erased));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM patients WHERE id = ?", Integer.class, erased)).isZero();
    }

    private static LoginRequest login(String email, String password) {
        LoginRequest request = new LoginRequest();
        request.setEmail(email);
        request.setPassword(password);
        return request;
    }
}
