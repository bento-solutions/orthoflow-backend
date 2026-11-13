package com.orthoflow.clinical;

import com.orthoflow.clinical.application.dto.AddToothFindingRequest;
import com.orthoflow.clinical.application.dto.ToothFindingResponse;
import com.orthoflow.clinical.application.service.ClinicalRecordService;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The clinical referential's two additions to a finding, through the real service, JPA and
 * PostgreSQL: a state the dentist only suspects stays suspected until it is confirmed, and a root of
 * a back tooth can be named in a surface that is now longer than the column used to allow.
 */
class FindingCertaintyFlowTest extends SpringDbTest {

    @Autowired
    private ClinicalRecordService service;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID doctor;
    private UUID patient;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        doctor = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'DOCTOR', ?)",
                doctor, doctor + "@x.ma", practice);
        signInAs(doctor, practice);
        patient = PostgresTestSupport.patient(jdbc, practice, "Sara", "Benziane", "1988-05-02", null, null);
    }

    private AddToothFindingRequest request(String code, String surface, String certainty) {
        AddToothFindingRequest request = new AddToothFindingRequest();
        request.setFindingCode(code);
        request.setSurface(surface);
        request.setCertainty(certainty);
        request.setSource("voice");
        return request;
    }

    private String paintedStatus(String fdi) {
        List<String> found = jdbc.queryForList("""
                SELECT ts.status FROM tooth_states ts JOIN dental_charts dc ON dc.id = ts.chart_id
                WHERE dc.patient_id = ? AND ts.fdi = ?
                """, String.class, patient, fdi);
        return found.isEmpty() ? null : found.get(0);
    }

    @Test
    void aSuspicionIsKeptAsOneAndDoesNotPaintTheTooth() {
        service.addFinding(patient, "26", request("caries", "mesial", "SUSPECTED"), doctor);

        ToothFindingResponse stored = service.listFindings(patient).get(0);
        assertThat(stored.certainty()).isEqualTo("SUSPECTED");
        assertThat(jdbc.queryForObject("SELECT certainty FROM tooth_findings WHERE fdi = '26' AND practice_id = ?", String.class, practice))
                .isEqualTo("SUSPECTED");
        assertThat(paintedStatus("26")).isEqualTo("present");
    }

    @Test
    void sayingItAgainWithNoDoubtConfirmsTheSameFinding() {
        service.addFinding(patient, "26", request("caries", "mesial", "SUSPECTED"), doctor);
        service.addFinding(patient, "26", request("caries", "mesial", "CONFIRMED"), doctor);

        List<ToothFindingResponse> findings = service.listFindings(patient);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).certainty()).isEqualTo("CONFIRMED");
        assertThat(paintedStatus("26")).isEqualTo("caries");
    }

    @Test
    void aFindingWithNoCertaintyIsConfirmed() {
        service.addFinding(patient, "16", request("existing_crown", null, null), doctor);

        assertThat(service.listFindings(patient).get(0).certainty()).isEqualTo("CONFIRMED");
        assertThat(paintedStatus("16")).isEqualTo("crown");
    }

    @Test
    void theHistoryCarriesTheCertaintyToo() {
        service.addFinding(patient, "36", request("necrosis", null, "SUSPECTED"), doctor);

        assertThat(service.listFindingHistory(patient)).extracting(ToothFindingResponse::certainty).containsExactly("SUSPECTED");
    }

    @Test
    void aSurfaceNamingEveryRootIsStoredWhole() {
        String longest = "occlusal-mesialroot-distalroot-buccalroot-lingualroot";

        service.addFinding(patient, "17", request("caries", longest, null), doctor);

        // 53 characters: more than the 48 the column held before.
        assertThat(longest.length()).isGreaterThan(48);
        assertThat(service.listFindings(patient).get(0).surface()).isEqualTo(longest);
    }

    @Test
    void theNewStatesAreRecordedAndPaintTheStatusTheyImply() {
        service.addFinding(patient, "47", request("root_fracture", "distalroot", null), doctor);
        service.addFinding(patient, "13", request("agenesis", null, null), doctor);
        service.addFinding(patient, "33", request("implant_crown", null, null), doctor);

        assertThat(paintedStatus("47")).isEqualTo("fracture");
        assertThat(paintedStatus("13")).isEqualTo("missing");
        assertThat(paintedStatus("33")).isEqualTo("implant");
    }
}
