package com.orthoflow.help;

import com.orthoflow.auth.infrastructure.security.JwtService;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.help.application.dto.GuideDtos.GuideKey;
import com.orthoflow.help.application.dto.GuideDtos.GuideProgress;
import com.orthoflow.help.application.dto.GuideDtos.GuideStatus;
import com.orthoflow.help.application.dto.GuideDtos.SaveGuideProgress;
import com.orthoflow.help.application.service.GuideProgressService;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The first-login guide's progress: each user sees only their own, saving twice is saving once,
 * a finished chapter never comes back on its own, and every "later" is counted.
 */
@AutoConfigureMockMvc
class GuideProgressFlowTest extends SpringDbTest {

    @Autowired
    private GuideProgressService service;
    @Autowired
    private JwtService jwt;
    @Autowired
    private MockMvc mockMvc;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID doctor;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        doctor = user(practice, "DOCTOR");
    }

    private UUID user(UUID clinic, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', ?, ?)",
                id, id + "@x.ma", role, clinic);
        return id;
    }

    private GuideProgress save(UUID user, GuideKey key, GuideStatus status, int version, String step) {
        return service.save(user, key, new SaveGuideProgress(status, version, step));
    }

    @Test
    void nothingIsRecordedForSomeoneWhoHasNotSeenTheGuide() {
        assertThat(service.mine(doctor)).isEmpty();
    }

    @Test
    void savingTheSameThingTwiceKeepsOneRow() {
        save(doctor, GuideKey.FIRST_RUN_DASHBOARD, GuideStatus.COMPLETED, 1, "search");
        GuideProgress again = save(doctor, GuideKey.FIRST_RUN_DASHBOARD, GuideStatus.COMPLETED, 1, "search");

        assertThat(again.status()).isEqualTo(GuideStatus.COMPLETED);
        assertThat(service.mine(doctor)).hasSize(1);
    }

    @Test
    void aFinishedChapterDoesNotGoBackDownUnlessTheContentChanged() {
        save(doctor, GuideKey.FIRST_RUN_VOICE, GuideStatus.COMPLETED, 1, "review");

        assertThat(save(doctor, GuideKey.FIRST_RUN_VOICE, GuideStatus.SNOOZED, 1, null).status()).isEqualTo(GuideStatus.COMPLETED);
        assertThat(save(doctor, GuideKey.FIRST_RUN_VOICE, GuideStatus.DISMISSED, 1, null).status()).isEqualTo(GuideStatus.COMPLETED);
        // a dismissed chapter can still be finished later from the replay
        save(doctor, GuideKey.FIRST_RUN_DOSSIER, GuideStatus.DISMISSED, 1, null);
        assertThat(save(doctor, GuideKey.FIRST_RUN_DOSSIER, GuideStatus.COMPLETED, 1, "consult").status()).isEqualTo(GuideStatus.COMPLETED);
        // new content is offered again
        GuideProgress v2 = save(doctor, GuideKey.FIRST_RUN_VOICE, GuideStatus.SNOOZED, 2, null);
        assertThat(v2.status()).isEqualTo(GuideStatus.SNOOZED);
        assertThat(v2.version()).isEqualTo(2);
    }

    @Test
    void everyLaterIsCounted() {
        save(doctor, GuideKey.FIRST_RUN_DASHBOARD, GuideStatus.SNOOZED, 1, null);
        GuideProgress second = save(doctor, GuideKey.FIRST_RUN_DASHBOARD, GuideStatus.SNOOZED, 1, null);
        assertThat(second.snoozeCount()).isEqualTo(2);

        GuideProgress done = save(doctor, GuideKey.FIRST_RUN_DASHBOARD, GuideStatus.COMPLETED, 1, "search");
        assertThat(done.snoozeCount()).isEqualTo(2);
        assertThat(done.lastStep()).isEqualTo("search");
    }

    @Test
    void aResetChapterIsOfferedAgain() {
        save(doctor, GuideKey.FIRST_RUN_DASHBOARD, GuideStatus.COMPLETED, 1, null);
        service.reset(doctor, GuideKey.FIRST_RUN_DASHBOARD);

        assertThat(service.mine(doctor)).isEmpty();
        assertThat(save(doctor, GuideKey.FIRST_RUN_DASHBOARD, GuideStatus.SNOOZED, 1, null).status()).isEqualTo(GuideStatus.SNOOZED);
    }

    @Test
    void anUnknownGuideIsABadRequest() {
        assertThatThrownBy(() -> GuideProgressService.key("TOUR_OF_EVERYTHING")).isInstanceOf(ValidationException.class);
        assertThat(GuideProgressService.key("first_run_voice")).isEqualTo(GuideKey.FIRST_RUN_VOICE);
    }

    // ── over HTTP: own rows only, the wire format, the errors ─────────────

    private String bearer(UUID user, String role) {
        return "Bearer " + jwt.generateToken(user, user + "@x.ma", role);
    }

    @Test
    void overHttpEachUserSeesOnlyTheirOwnProgress() throws Exception {
        signOut();
        UUID colleague = user(practice, "DOCTOR");
        UUID otherClinic = user(PostgresTestSupport.newPractice(jdbc), "ADMIN");

        mockMvc.perform(put("/me/guides/FIRST_RUN_DASHBOARD").header("Authorization", bearer(doctor, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\",\"version\":1,\"lastStep\":\"search\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("FIRST_RUN_DASHBOARD"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.snoozeCount").value(0));

        mockMvc.perform(get("/me/guides").header("Authorization", bearer(doctor, "DOCTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(get("/me/guides").header("Authorization", bearer(colleague, "DOCTOR")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(get("/me/guides").header("Authorization", bearer(otherClinic, "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));

        // a colleague resetting "their" chapter leaves the doctor's alone
        mockMvc.perform(delete("/me/guides/FIRST_RUN_DASHBOARD").header("Authorization", bearer(colleague, "DOCTOR")))
                .andExpect(status().isNoContent());
        assertThat(service.mine(doctor)).hasSize(1);

        mockMvc.perform(put("/me/guides/NOT_A_GUIDE").header("Authorization", bearer(doctor, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\",\"version\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/me/guides/FIRST_RUN_VOICE").header("Authorization", bearer(doctor, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"FINISHED\",\"version\":1}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/me/guides/FIRST_RUN_VOICE").header("Authorization", bearer(doctor, "DOCTOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/me/guides")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/me/guides/FIRST_RUN_VOICE")
                .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"COMPLETED\",\"version\":1}"))
                .andExpect(status().isUnauthorized());
    }
}
