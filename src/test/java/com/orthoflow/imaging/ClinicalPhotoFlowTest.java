package com.orthoflow.imaging;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.imaging.application.dto.ClinicalPhotoDtos.Photo;
import com.orthoflow.imaging.application.dto.ClinicalPhotoDtos.Series;
import com.orthoflow.imaging.application.dto.ClinicalPhotoDtos.SeriesRequest;
import com.orthoflow.imaging.application.service.ClinicalPhotoService;
import com.orthoflow.imaging.domain.model.PhotoStage;
import com.orthoflow.imaging.domain.model.PhotoViewType;
import com.orthoflow.patient.application.service.PatientService;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Orthodontic photo series through the real services, database and file store: a
 * series per sitting, one picture per view, replacing and removing them, and
 * erasure taking the bytes with the patient.
 */
@TestPropertySource(properties = "orthoflow.storage.local-root=${java.io.tmpdir}/orthoflow-clinical-photo-test")
class ClinicalPhotoFlowTest extends SpringDbTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    @Autowired
    private ClinicalPhotoService service;
    @Autowired
    private FileService files;
    @Autowired
    private PatientService patients;

    private JdbcTemplate jdbc;
    private UUID practice;
    private UUID user;
    private UUID patient;

    @BeforeEach
    void setUp() {
        jdbc = PostgresTestSupport.jdbc();
        practice = PostgresTestSupport.newPractice(jdbc);
        user = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, first_name, last_name, role, practice_id) VALUES (?, ?, 'x', 'Nabil', 'Farrak', 'DOCTOR', ?)",
                user, user + "@x.ma", practice);
        signInAs(user, practice);
        patient = PostgresTestSupport.patient(jdbc, practice, "Mohamed", "El Adawy", "1995-03-01", null, null);
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    private Series newSeries(PhotoStage stage, LocalDate on) {
        return service.create(practice, user, patient, new SeriesRequest(stage, on, null));
    }

    @Test
    void aSeriesHoldsOnePicturePerViewAndListsTheMostRecentSittingFirst() {
        Series initial = newSeries(PhotoStage.INITIAL, LocalDate.of(2026, 1, 10));
        Series progress = newSeries(PhotoStage.PROGRESS, LocalDate.of(2026, 6, 2));

        service.upload(practice, user, initial.id(), PhotoViewType.SMILE, file("smile.png", PNG));
        Series after = service.upload(practice, user, initial.id(), PhotoViewType.PANORAMIC_XRAY, file("pano.jpg", JPEG));

        assertThat(after.photos()).extracting(Photo::view).containsExactly(PhotoViewType.SMILE, PhotoViewType.PANORAMIC_XRAY);
        assertThat(after.photos().get(1).contentType()).isEqualTo("image/jpeg");

        List<Series> listed = service.list(practice, patient);
        assertThat(listed).extracting(Series::id).containsExactly(progress.id(), initial.id());
        assertThat(listed.get(1).photos()).hasSize(2);
        assertThat(listed.get(0).photos()).isEmpty();
    }

    @Test
    void replacingAViewKeepsOnePictureAndDropsTheOldFile() {
        Series series = newSeries(PhotoStage.INITIAL, LocalDate.of(2026, 1, 10));
        UUID first = service.upload(practice, user, series.id(), PhotoViewType.PROFILE, file("a.png", PNG)).photos().get(0).fileId();

        Series replaced = service.upload(practice, user, series.id(), PhotoViewType.PROFILE, file("b.jpg", JPEG));

        assertThat(replaced.photos()).hasSize(1);
        assertThat(replaced.photos().get(0).fileId()).isNotEqualTo(first);
        assertThat(replaced.photos().get(0).name()).isEqualTo("b.jpg");
        assertThatThrownBy(() -> files.require(practice, first)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void onlyPicturesAreAccepted() {
        Series series = newSeries(PhotoStage.INITIAL, LocalDate.of(2026, 1, 10));

        assertThatThrownBy(() -> service.upload(practice, user, series.id(), PhotoViewType.SMILE, file("scan.pdf", PDF)))
                .isInstanceOf(ValidationException.class);
        assertThat(service.list(practice, patient).get(0).photos()).isEmpty();
    }

    @Test
    void removingAViewOrASeriesDeletesItsFiles() {
        Series series = newSeries(PhotoStage.FINAL, LocalDate.of(2026, 9, 1));
        UUID smile = service.upload(practice, user, series.id(), PhotoViewType.SMILE, file("s.png", PNG)).photos().get(0).fileId();
        Series withTwo = service.upload(practice, user, series.id(), PhotoViewType.FRONTAL_OCCLUSION, file("f.png", PNG));
        UUID frontal = withTwo.photos().stream().filter(p -> p.view() == PhotoViewType.FRONTAL_OCCLUSION).findFirst().orElseThrow().fileId();

        Series afterRemove = service.removePhoto(practice, series.id(), PhotoViewType.SMILE);
        assertThat(afterRemove.photos()).extracting(Photo::view).containsExactly(PhotoViewType.FRONTAL_OCCLUSION);
        assertThatThrownBy(() -> files.require(practice, smile)).isInstanceOf(NotFoundException.class);

        service.delete(practice, series.id());
        assertThat(service.list(practice, patient)).isEmpty();
        assertThatThrownBy(() -> files.require(practice, frontal)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void anotherClinicCannotReachTheSeries() {
        Series series = newSeries(PhotoStage.INITIAL, LocalDate.of(2026, 1, 10));
        UUID other = PostgresTestSupport.newPractice(jdbc);
        signInTo(other);

        assertThatThrownBy(() -> service.upload(other, user, series.id(), PhotoViewType.SMILE, file("s.png", PNG)))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.list(other, patient)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void erasingThePatientRemovesTheSeriesAndTheirFiles() {
        Series series = newSeries(PhotoStage.INITIAL, LocalDate.of(2026, 1, 10));
        service.upload(practice, user, series.id(), PhotoViewType.SMILE, file("s.png", PNG));

        patients.erasePatient(patient);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM clinical_photo_series WHERE patient_id = ?", Integer.class, patient)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM files WHERE owner_id = ? AND owner_type = 'CLINICAL_PHOTO'", Integer.class, patient)).isZero();
    }
}
