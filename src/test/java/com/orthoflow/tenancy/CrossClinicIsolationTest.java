package com.orthoflow.tenancy;

import com.orthoflow.auth.infrastructure.security.JwtService;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.numbering.DocumentNumbers;
import com.orthoflow.common.tenancy.Tenancy;
import com.orthoflow.patient.application.service.PatientService;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.infrastructure.adapter.persistence.PatientJpaRepository;
import com.orthoflow.publicapi.application.service.PublicLinkRequestClinic;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.hibernate.annotations.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two clinics on one database (ADR 0007). Clinic A has data in the modules that were
 * written before tenancy (patients, the treatment catalogue, stock, invoices, the chart);
 * clinic B, signed in, must see none of it, however it asks: listing, by id, over HTTP.
 */
@AutoConfigureMockMvc
class CrossClinicIsolationTest extends SpringDbTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private PatientJpaRepository patients;
    @Autowired
    private PatientService patientService;
    @Autowired
    private Tenancy tenancy;
    @Autowired
    private TransactionTemplate tx;
    @Autowired
    private DocumentNumbers numbers;
    @Autowired
    private PublicLinkService links;
    @Autowired
    private PublicLinkRequestClinic publicLinkClinic;
    @Autowired
    private JwtService jwt;
    @Autowired
    private MockMvc mockMvc;

    private JdbcTemplate jdbc;
    private UUID clinicA;
    private UUID clinicB;
    private UUID patientA;
    private UUID treatmentA;
    private UUID invoiceA;
    private UUID adminB;

    @BeforeEach
    void twoClinics() {
        jdbc = PostgresTestSupport.jdbc();
        clinicA = PostgresTestSupport.newPractice(jdbc);
        clinicB = PostgresTestSupport.newPractice(jdbc);
        patientA = PostgresTestSupport.patient(jdbc, clinicA, "Sara", "Benziane", "2010-01-01", "0611223344", null);
        UUID userA = user(clinicA);
        adminB = user(clinicB);

        treatmentA = UUID.randomUUID();
        jdbc.update("INSERT INTO treatments (id, name, code, base_price, practice_id) VALUES (?, 'Pose bagues', ?, 5000, ?)",
                treatmentA, "TA-" + treatmentA, clinicA);
        jdbc.update("INSERT INTO stock_items (id, name, sku, category, unit, unit_size, unit_label, purchase_price, price_per_use, practice_id) "
                + "VALUES (?, 'Brackets', ?, 'CONSUMABLE', 'BOX', 1, 'box', 10, 1, ?)", UUID.randomUUID(), "SKU-" + clinicA, clinicA);
        jdbc.update("INSERT INTO suppliers (id, name, practice_id) VALUES (?, 'Ortho Labo', ?)", UUID.randomUUID(), clinicA);
        invoiceA = UUID.randomUUID();
        jdbc.update("INSERT INTO invoices (id, practice_id, patient_id, invoice_number, status, currency, total, region_code, created_by) "
                + "VALUES (?, ?, ?, 'INV-A-1', 'DRAFT', 'MAD', 100, 'MA', ?)", invoiceA, clinicA, patientA, userA);
        jdbc.update("INSERT INTO invoice_lines (id, invoice_id, act_code, label, quantity, unit_price, discount_pct, line_total, sort_order, practice_id) "
                + "VALUES (?, ?, 'D700', 'Obturation', 1, 100, 0, 100, 0, ?)", UUID.randomUUID(), invoiceA, clinicA);
        UUID chart = UUID.randomUUID();
        jdbc.update("INSERT INTO dental_charts (id, patient_id, chart_type, practice_id) VALUES (?, ?, 'adult', ?)", chart, patientA, clinicA);
        jdbc.update("INSERT INTO clinical_notes (id, patient_id, category, content, author_id, practice_id) VALUES (?, ?, 'GENERAL', 'note', ?, ?)",
                UUID.randomUUID(), patientA, userA, clinicA);
    }

    private UUID user(UUID clinic) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'A', 'B', 'ADMIN', ?)",
                id, id + "@x.ma", clinic);
        return id;
    }

    @Test
    void noRepositoryShowsAnotherClinicsRows() {
        signInTo(clinicB);
        List<String> leaks = new ArrayList<>();
        for (Map.Entry<String, JpaRepository> repo : context.getBeansOfType(JpaRepository.class).entrySet()) {
            for (Object row : repo.getValue().findAll()) {
                Object owner = tenantOf(row);
                if (owner != null && !clinicB.equals(owner)) {
                    leaks.add(repo.getKey() + " -> " + row.getClass().getSimpleName() + " of " + owner);
                }
            }
        }
        assertThat(leaks).isEmpty();
    }

    @Test
    void theClinicItselfSeesItsRows() {
        signInTo(clinicA);
        assertThat(patients.findAll()).extracting(Patient::getId).contains(patientA);
    }

    @Test
    void anotherClinicsPatientIsNotFoundById() {
        signInTo(clinicB);
        assertThatThrownBy(() -> patients.findById(patientA)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> patientService.getPatient(patientA)).isInstanceOf(NotFoundException.class);
        assertThat(patients.existsById(patientA)).isFalse();
    }

    @Test
    void aRowCannotBeWrittenIntoAnotherClinic() {
        signInTo(clinicB);
        Patient intruder = Patient.builder().practiceId(clinicA).firstName("X").lastName("Y").patientCode("P-X")
                .status("ACTIVE").build();
        assertThatThrownBy(() -> patients.saveAndFlush(intruder)).isInstanceOf(RuntimeException.class);
        Integer inA = jdbc.queryForObject("SELECT count(*) FROM patients WHERE practice_id = ? AND patient_code = 'P-X'", Integer.class, clinicA);
        assertThat(inA).isZero();
    }

    @Test
    void changesMadeByOneClinicNeverReachAnother() {
        signInTo(clinicB);
        tx.executeWithoutResult(s -> patients.findAll().forEach(p -> p.setFirstName("Changed")));
        String name = jdbc.queryForObject("SELECT first_name FROM patients WHERE id = ?", String.class, patientA);
        assertThat(name).isEqualTo("Sara");
    }

    @Test
    void withNoSignedInUserAndNoClinicNothingIsRead() {
        assertThat(patients.findAll()).isEmpty();
        assertThat(patients.count()).isZero();
    }

    @Test
    void workAcrossClinicsKeepsTheClinicItNamesAndCannotLeaveItOut() {
        UUID named = UUID.randomUUID();
        tenancy.runAcrossClinics(() -> patients.saveAndFlush(Patient.builder().id(named).practiceId(clinicA)
                .firstName("Nadia").lastName("Alami").patientCode("P-N").build()));
        UUID owner = jdbc.queryForObject("SELECT practice_id FROM patients WHERE id = ?", UUID.class, named);
        assertThat(owner).isEqualTo(clinicA);

        assertThatThrownBy(() -> tenancy.runAcrossClinics(() -> patients.saveAndFlush(Patient.builder()
                .firstName("No").lastName("Clinic").patientCode("P-0").build()))).isInstanceOf(RuntimeException.class);
    }

    @Test
    void eachClinicNumbersItsOwnDocuments() {
        long a = tenancy.callAs(clinicA, () -> tx.execute(s -> numbers.next("invoice")));
        long b = tenancy.callAs(clinicB, () -> tx.execute(s -> numbers.next("invoice")));
        long a2 = tenancy.callAs(clinicA, () -> tx.execute(s -> numbers.next("invoice")));
        assertThat(a).isEqualTo(1);
        assertThat(b).isEqualTo(1);
        assertThat(a2).isEqualTo(2);
    }

    @Test
    void aPublicPageWorksForTheClinicWhoseLinkItIs() {
        signInTo(clinicA);
        String token = links.sharedToken(clinicA, PublicLinkPurpose.BOOKING);
        signOut();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/public/book/" + token);
        request.setServletPath("/public/book/" + token);
        assertThat(publicLinkClinic.clinicFor(request)).contains(clinicA);

        MockHttpServletRequest unknown = new MockHttpServletRequest("GET", "/public/book/not-a-real-token-at-all-0000");
        unknown.setServletPath("/public/book/not-a-real-token-at-all-0000");
        assertThat(publicLinkClinic.clinicFor(unknown)).contains(com.orthoflow.common.tenancy.TenantContext.NONE);
    }

    @Test
    void overHttpAnotherClinicsDataIsInvisible() throws Exception {
        String bearer = "Bearer " + jwt.generateToken(adminB, adminB + "@x.ma", "ADMIN");

        mockMvc.perform(get("/patients/" + patientA).header("Authorization", bearer)).andExpect(status().isNotFound());
        mockMvc.perform(get("/invoices/" + invoiceA).header("Authorization", bearer)).andExpect(status().isNotFound());
        mockMvc.perform(get("/stock/treatments/" + treatmentA).header("Authorization", bearer)).andExpect(status().isNotFound());

        for (String list : List.of("/patients", "/invoices", "/stock/treatments", "/stock/items", "/stock/suppliers")) {
            String body = mockMvc.perform(get(list).header("Authorization", bearer)).andReturn().getResponse().getContentAsString();
            assertThat(body).as(list).doesNotContain(patientA.toString()).doesNotContain(treatmentA.toString())
                    .doesNotContain(invoiceA.toString()).doesNotContain("Benziane").doesNotContain("Ortho Labo");
        }
    }

    private static Object tenantOf(Object entity) {
        for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.isAnnotationPresent(TenantId.class)) {
                    f.setAccessible(true);
                    try {
                        return f.get(entity);
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException(e);
                    }
                }
            }
        }
        return null;
    }
}
