package com.orthoflow.insurance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.insurance.application.InsuranceFormService;
import com.orthoflow.insurance.application.dto.InsuranceFormDtos;
import com.orthoflow.insurance.application.port.SessionInsuranceForms.SessionAct;
import com.orthoflow.insurance.domain.model.InsuranceForm;
import com.orthoflow.prescription.application.PrescriptionService;
import com.orthoflow.prescription.application.dto.PrescriptionDtos;
import com.orthoflow.tasks.application.service.TaskService;
import com.orthoflow.tasks.domain.model.Task;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import com.orthoflow.treatment.application.dto.TreatmentRequest;
import com.orthoflow.treatment.application.service.TreatmentService;
import com.orthoflow.treatment.domain.model.Treatment;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The end of a session against the real schema (V60): the patient's CNOPS sheet is filled,
 * kept, and lands in the front desk's tasks with a link to it; another clinic sees none of it.
 */
class InsuranceFormDbTest extends SpringDbTest {

    @Autowired
    private InsuranceFormService forms;
    @Autowired
    private TaskService tasks;
    @Autowired
    private TreatmentService treatments;
    @Autowired
    private PrescriptionService prescriptions;

    @Test
    void aSessionFillsThePatientsCnopsSheetAndHandsItToTheFrontDesk() throws Exception {
        JdbcTemplate jdbc = PostgresTestSupport.jdbc();
        UUID practice = PostgresTestSupport.newPractice(jdbc);
        UUID doctorUser = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'Salma', 'Amrani', 'DOCTOR', ?)",
                doctorUser, doctorUser + "@x.ma", practice);
        jdbc.update("INSERT INTO practitioners (id, practice_id, user_id, display_name, inpe) VALUES (?, ?, ?, 'Dr Salma Amrani', '123456789')",
                UUID.randomUUID(), practice, doctorUser);
        UUID cnops = UUID.randomUUID();
        jdbc.update("INSERT INTO insurers (id, practice_id, code, name, kind) VALUES (?, ?, 'CNOPS', 'CNOPS', 'PUBLIC')", cnops, practice);
        UUID patient = PostgresTestSupport.patient(jdbc, practice, "Yasmine", "Bennani", "2014-03-14", "0661234567", null);
        jdbc.update("""
                UPDATE patients SET insurer_id = ?, insurance_number = '123456789', insurance_affiliation_number = '1234567',
                       insured_relation = 'CHILD', insured_name = 'BENNANI Karim', insured_cin = 'BK123456', gender = 'F'
                WHERE id = ?""", cnops, patient);
        signInAs(doctorUser, practice);
        Treatment odf = treatments.saveTreatment(treatment("ODF-S1", "Traitement ODF, 1er semestre", "D629"), null);

        List<InsuranceFormDtos.Issued> issued = forms.afterSession(practice, doctorUser, null, patient, List.of(
                new SessionAct(null, "Consultation", null, new BigDecimal("250"), true),
                new SessionAct(odf.getId(), null, null, new BigDecimal("3000"), false)));

        assertThat(issued).extracting(InsuranceFormDtos.Issued::purpose)
                .containsExactly(InsuranceForm.Purpose.EXECUTION, InsuranceForm.Purpose.PRIOR_AGREEMENT);
        InsuranceFormDtos.View agreement = forms.get(practice, issued.get(1).id());
        assertThat(agreement.formCode()).isEqualTo("cnops-dentaire");
        assertThat(agreement.lines()).singleElement().satisfies(l -> {
            assertThat(l.code()).isEqualTo("D629");
            assertThat(l.cotation()).isEqualTo("D 90");
            assertThat(l.label()).isEqualTo("Traitement ODF, 1er semestre");
        });
        // The consultation line has no NGAP code: the front desk is told to add it.
        assertThat(forms.get(practice, issued.get(0).id()).missing()).containsExactly("ACT_CODES");
        assertThat(agreement.missing()).isEmpty();

        try (PDDocument pdf = PDDocument.load(forms.file(practice, agreement.id(), false))) {
            String front = text(pdf, 1);
            assertThat(front).contains("BENNANI Karim").contains("BENNANI Yasmine").contains("3 000,00");
            assertThat(text(pdf, 2)).contains("D629").contains("D 90");
        }

        var open = tasks.all(practice, Task.Status.OPEN, null, patient);
        assertThat(open).hasSize(2).allSatisfy(t -> {
            assertThat(t.documentKind()).isEqualTo(Task.DocumentKind.INSURANCE_FORM);
            assertThat(t.assigneeRole()).hasToString("ASSISTANT");
        });
        assertThat(open).extracting(t -> t.documentId()).containsExactlyInAnyOrder(issued.get(0).id(), issued.get(1).id());

        forms.markHandedOver(practice, agreement.id());
        assertThat(forms.list(practice, patient, InsuranceForm.Status.HANDED_OVER)).hasSize(1);

        // A prescription goes through the same schema: library, adoption, issue.
        assertThat(prescriptions.library(practice)).hasSize(12);
        var template = prescriptions.adopt(practice, doctorUser, "aphtes");
        assertThat(template.reviewed()).isFalse();
        var rx = prescriptions.issue(practice, doctorUser, new PrescriptionDtos.IssueRequest(patient, null, null, template.id(),
                template.lines(), template.advice(), false));
        assertThat(rx.number()).startsWith("ORD-");
        assertThat(prescriptions.list(practice, patient)).extracting(PrescriptionDtos.View::id).containsExactly(rx.id());

        // A patient whose insurer was typed rather than picked still gets their insurer's sheet.
        jdbc.update("INSERT INTO insurers (id, practice_id, code, name, kind) VALUES (?, ?, 'CNSS', 'CNSS (AMO)', 'PUBLIC')",
                UUID.randomUUID(), practice);
        UUID typed = PostgresTestSupport.patient(jdbc, practice, "Omar", "Tazi", "1980-01-02", "0600000000", "AB12345");
        jdbc.update("UPDATE patients SET insurance_provider = 'cnss', insurance_number = '987654321' WHERE id = ?", typed);
        assertThat(forms.preview(practice, doctorUser, typed, null).formCode()).isEqualTo("cnss-dentaire");

        // Another clinic reads none of it.
        signInTo(PostgresTestSupport.newPractice(jdbc));
        assertThatThrownBy(() -> forms.get(practice, agreement.id())).isInstanceOf(NotFoundException.class);
    }

    private static TreatmentRequest treatment(String code, String name, String actCode) {
        TreatmentRequest r = new TreatmentRequest();
        r.setName(name);
        r.setCode(code);
        r.setBasePrice(new BigDecimal("3000"));
        r.setActCode(actCode);
        return r;
    }

    private static String text(PDDocument doc, int page) throws Exception {
        PDFTextStripper s = new PDFTextStripper();
        s.setStartPage(page);
        s.setEndPage(page);
        return s.getText(doc).replaceAll("\\s+", " ");
    }
}
