package com.orthoflow.storage;

import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.storage.application.port.StorageService;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.DocumentCategory;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import com.orthoflow.storage.infrastructure.StoredFileJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A patient document says what it is, and the three kinds the dossier displays are pictures. */
class DocumentCategoryTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] PDF = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    private final UUID practice = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private StoredFileJpaRepository repo;
    private FileService service;

    @BeforeEach
    void setUp() {
        repo = mock(StoredFileJpaRepository.class);
        when(repo.save(any(StoredFile.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new FileService(repo, mock(StorageService.class));
    }

    private static MockMultipartFile upload(byte[] bytes) {
        return new MockMultipartFile("file", "pano.png", "image/png", bytes);
    }

    @Test
    void keepsTheCategoryOfAPatientDocument() {
        StoredFile f = service.store(practice, FileOwnerType.PATIENT_DOCUMENT, patient, upload(PNG), DocumentCategory.XRAY, null);
        assertThat(f.getCategory()).isEqualTo(DocumentCategory.XRAY);
        assertThat(f.getContentType()).isEqualTo("image/png");
    }

    @Test
    void anUploadWithoutACategoryStaysWithout() {
        assertThat(service.store(practice, FileOwnerType.PATIENT_DOCUMENT, patient, upload(PDF), null).getCategory()).isNull();
    }

    @Test
    void aCategorisedDocumentMustBeAPicture() {
        assertThatThrownBy(() -> service.store(practice, FileOwnerType.PATIENT_DOCUMENT, patient, upload(PDF), DocumentCategory.INTRAORAL_3D, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void onlyAPatientDocumentHasACategory() {
        assertThatThrownBy(() -> service.store(practice, FileOwnerType.EXPENSE_RECEIPT, UUID.randomUUID(), upload(PNG), DocumentCategory.IMAGE, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void recategorisesAPicture() {
        UUID id = UUID.randomUUID();
        StoredFile file = StoredFile.builder().id(id).practiceId(practice).ownerType(FileOwnerType.PATIENT_DOCUMENT)
                .ownerId(patient).contentType("image/jpeg").category(DocumentCategory.IMAGE).build();
        when(repo.findByIdAndPracticeIdAndDeletedAtIsNull(id, practice)).thenReturn(Optional.of(file));
        assertThat(service.recategorise(practice, id, DocumentCategory.XRAY).getCategory()).isEqualTo(DocumentCategory.XRAY);
    }

    @Test
    void aPdfCannotBecomeAnXray() {
        UUID id = UUID.randomUUID();
        StoredFile file = StoredFile.builder().id(id).practiceId(practice).ownerType(FileOwnerType.PATIENT_DOCUMENT)
                .ownerId(patient).contentType("application/pdf").build();
        when(repo.findByIdAndPracticeIdAndDeletedAtIsNull(id, practice)).thenReturn(Optional.of(file));
        assertThatThrownBy(() -> service.recategorise(practice, id, DocumentCategory.XRAY)).isInstanceOf(ValidationException.class);
    }
}
