package com.orthoflow.clinical.application.service;

import com.orthoflow.clinical.application.dto.AddToothFindingRequest;
import com.orthoflow.clinical.application.dto.ToothFindingResponse;
import com.orthoflow.clinical.domain.model.ChartType;
import com.orthoflow.clinical.domain.model.DentalChart;
import com.orthoflow.clinical.domain.model.FindingCatalog;
import com.orthoflow.clinical.domain.model.FindingKind;
import com.orthoflow.clinical.domain.model.ToothFinding;
import com.orthoflow.clinical.domain.model.ToothState;
import com.orthoflow.clinical.domain.repository.ClinicalNoteRepository;
import com.orthoflow.clinical.domain.repository.DentalChartRepository;
import com.orthoflow.clinical.domain.repository.MedicalHistoryRepository;
import com.orthoflow.clinical.domain.repository.PatientAllergyRepository;
import com.orthoflow.clinical.domain.repository.ToothFindingRepository;
import com.orthoflow.clinical.domain.repository.ToothStateEventRepository;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.patient.domain.model.Patient;
import com.orthoflow.patient.domain.repository.PatientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The clinical referential ("Référentiel des états cliniques dentaires") in the record: every state
 * of its catalogue is a state the server accepts, a suspicion stays a suspicion until it is
 * confirmed, and a root of a back tooth can be named.
 */
class FindingReferentialTest {

    /** The single statuses the odontogram can paint: what a state may imply. */
    private static final Set<String> PAINTED_STATUSES = Set.of(
            "present", "extracted", "missing", "caries", "fracture", "impacted", "composite", "amalgam",
            "crown", "bridge", "veneer", "implant", "post", "root_canal", "deciduous");

    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID ACTOR = UUID.randomUUID();

    private final List<ToothFinding> store = new ArrayList<>();
    private DentalChart chart;
    private ClinicalRecordService service;

    @BeforeEach
    void setUp() {
        ToothFindingRepository findings = mock(ToothFindingRepository.class);
        DentalChartRepository charts = mock(DentalChartRepository.class);
        PatientRepository patients = mock(PatientRepository.class);
        chart = DentalChart.builder().id(UUID.randomUUID()).patientId(PATIENT).chartType(ChartType.adult).build();
        when(patients.findById(PATIENT)).thenReturn(Optional.of(mock(Patient.class)));
        when(charts.findByPatientId(PATIENT)).thenReturn(Optional.of(chart));
        when(charts.save(any())).thenAnswer(i -> i.getArgument(0));
        when(findings.findActiveByChartAndFdi(any(), any())).thenAnswer(i -> List.copyOf(store));
        when(findings.findActiveByChartFdiCodeAndSurface(any(), any(), any(), any())).thenAnswer(i -> {
            String code = i.getArgument(2);
            String surface = i.getArgument(3);
            return store.stream()
                    .filter(f -> f.getFindingCode().equals(code) && Objects.equals(f.getSurface(), surface))
                    .findFirst();
        });
        when(findings.save(any())).thenAnswer(i -> {
            ToothFinding finding = i.getArgument(0);
            if (finding.getId() == null) finding.prePersist();
            if (!store.contains(finding)) store.add(finding);
            return finding;
        });
        service = new ClinicalRecordService(charts, findings, mock(ClinicalNoteRepository.class),
                mock(PatientAllergyRepository.class), mock(MedicalHistoryRepository.class),
                mock(ToothStateEventRepository.class), patients);
    }

    private AddToothFindingRequest request(String code, String surface, String certainty) {
        AddToothFindingRequest request = new AddToothFindingRequest();
        request.setFindingCode(code);
        request.setSurface(surface);
        request.setCertainty(certainty);
        request.setSource("voice");
        return request;
    }

    private String statusOf(String fdi) {
        return chart.getToothStates().stream().filter(t -> t.getFdi().equals(fdi)).map(ToothState::getStatus)
                .findFirst().orElse(null);
    }

    // ── The catalogue ───────────────────────────────────────────────────

    @Test
    void theCatalogueHoldsEveryStateOfTheReferential() {
        // The 106 states the referential added, to the 57 of the base catalogue.
        assertThat(FindingCatalog.codes()).hasSize(163);
        for (String code : List.of(
                "agenesis", "unerupted", "persistent_deciduous", "cracked_tooth", "craze_lines", "cusp_fracture",
                "root_fracture", "existing_glass_ionomer", "existing_onlay", "existing_overlay", "bridge_pontic",
                "implant_crown", "post_and_crown", "marginal_defect", "irreversible_pulpitis_symptomatic",
                "endo_treatment_started", "internal_resorption", "retreatment_required", "separated_instrument",
                "gingivitis", "periodontitis", "bleeding_on_probing", "clinical_attachment_loss", "furcation_involvement",
                "calculus", "plaque", "peri_implantitis", "crowding", "deep_bite", "occlusal_interference",
                "extrusive_luxation", "complicated_crown_fracture", "ankylosis", "percussion_sensitivity")) {
            assertThat(FindingCatalog.isKnown(code)).as(code).isTrue();
        }
    }

    @Test
    void everyStateImpliesOnlyAStatusTheChartCanPaint() {
        FindingCatalog.all().forEach((code, definition) -> {
            if (definition.impliedStatus() != null) {
                assertThat(PAINTED_STATUSES).as(code).contains(definition.impliedStatus());
            }
        });
    }

    @Test
    void aStateThatIsAKindOfFractureOrAbsencePaintsAsOne() {
        for (String code : List.of("cracked_tooth", "cusp_fracture", "crown_fracture", "root_fracture", "enamel_fracture",
                "enamel_dentin_fracture", "complicated_crown_fracture", "crown_root_fracture")) {
            assertThat(FindingCatalog.primaryStatus(List.of(code))).as(code).isEqualTo("fracture");
        }
        assertThat(FindingCatalog.primaryStatus(List.of("agenesis"))).isEqualTo("missing");
        assertThat(FindingCatalog.primaryStatus(List.of("unerupted"))).isEqualTo("impacted");
        assertThat(FindingCatalog.primaryStatus(List.of("implant_crown"))).isEqualTo("implant");
        assertThat(FindingCatalog.primaryStatus(List.of("post_and_crown"))).isEqualTo("crown");
        assertThat(FindingCatalog.primaryStatus(List.of("bridge_abutment", "bridge_pontic"))).isEqualTo("bridge");
    }

    @Test
    void theKindsFollowTheDifferenceBetweenAStateAndAnAct() {
        // "Nécrose pulpaire" is a diagnosis; "traitement canalaire réalisé" is history.
        assertThat(FindingCatalog.get("necrosis").orElseThrow().kind()).isEqualTo(FindingKind.CONDITION);
        assertThat(FindingCatalog.get("existing_root_canal").orElseThrow().kind()).isEqualTo(FindingKind.EXISTING);
        assertThat(FindingCatalog.get("retreatment_required").orElseThrow().kind()).isEqualTo(FindingKind.TREATMENT_REQUIRED);
        assertThat(FindingCatalog.get("healthy_gingiva").orElseThrow().kind()).isEqualTo(FindingKind.OBSERVATION);
        assertThat(FindingCatalog.get("existing_onlay").orElseThrow().kind()).isEqualTo(FindingKind.EXISTING);
    }

    @Test
    void aStateTheCatalogueDoesNotKnowIsRefused() {
        assertThatThrownBy(() -> service.addFinding(PATIENT, "16", request("not_a_state", null, null), ACTOR))
                .isInstanceOf(ValidationException.class);
    }

    // ── Suspected is not found ──────────────────────────────────────────

    @Test
    void aSuspicionIsRecordedAsOne() {
        ToothFindingResponse response = service.addFinding(PATIENT, "16", request("caries", "mesial", "SUSPECTED"), ACTOR);

        assertThat(response.certainty()).isEqualTo("SUSPECTED");
        assertThat(store.get(0).getCertainty().name()).isEqualTo("SUSPECTED");
    }

    @Test
    void aFindingIsConfirmedUnlessSaidOtherwise() {
        assertThat(service.addFinding(PATIENT, "16", request("caries", null, null), ACTOR).certainty()).isEqualTo("CONFIRMED");
        assertThat(service.addFinding(PATIENT, "17", request("caries", null, ""), ACTOR).certainty()).isEqualTo("CONFIRMED");
    }

    @Test
    void aSuspicionDoesNotPaintTheToothUntilItIsConfirmed() {
        service.addFinding(PATIENT, "16", request("caries", "mesial", "SUSPECTED"), ACTOR);
        assertThat(statusOf("16")).isEqualTo("present");

        // The same finding said again with no doubt: confirmed, and now it is the tooth's state.
        ToothFindingResponse confirmed = service.addFinding(PATIENT, "16", request("caries", "mesial", "CONFIRMED"), ACTOR);
        assertThat(confirmed.certainty()).isEqualTo("CONFIRMED");
        assertThat(store).hasSize(1);
        assertThat(statusOf("16")).isEqualTo("caries");
    }

    @Test
    void aConfirmedFindingStillPaintsBesideASuspectedOne() {
        service.addFinding(PATIENT, "26", request("existing_crown", null, "CONFIRMED"), ACTOR);
        service.addFinding(PATIENT, "26", request("necrosis", null, "SUSPECTED"), ACTOR);

        assertThat(statusOf("26")).isEqualTo("crown");
    }

    @Test
    void anUnknownCertaintyIsRefused() {
        assertThatThrownBy(() -> service.addFinding(PATIENT, "16", request("caries", null, "MAYBE"), ACTOR))
                .isInstanceOf(ValidationException.class);
    }

    // ── Roots ───────────────────────────────────────────────────────────

    @Test
    void aRootOfABackToothCanBeNamed() {
        ToothFindingResponse response = service.addFinding(PATIENT, "46",
                request("root_fracture", "mesialroot", null), ACTOR);

        assertThat(response.surface()).isEqualTo("mesialroot");
    }

    @Test
    void theSameFindingInTwoRootsIsTwoLesions() {
        service.addFinding(PATIENT, "46", request("periapical_lesion", "mesialroot", null), ACTOR);
        service.addFinding(PATIENT, "46", request("periapical_lesion", "distalroot", null), ACTOR);

        assertThat(store).extracting(ToothFinding::getSurface).containsExactlyInAnyOrder("mesialroot", "distalroot");
    }

    @Test
    void aLongCompoundSurfaceWithRootsStillFits() {
        String longest = "occlusal-mesialroot-distalroot-buccalroot-lingualroot";

        ToothFindingResponse response = service.addFinding(PATIENT, "16", request("caries", longest, null), ACTOR);

        assertThat(response.surface()).isEqualTo(longest);
        assertThat(longest.length()).isLessThanOrEqualTo(64);
    }
}
