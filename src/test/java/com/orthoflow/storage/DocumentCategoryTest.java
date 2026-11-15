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

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** A patient document says what it is, and an intraoral scan may be a 3D model. */
class DocumentCategoryTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};

    private final UUID practice = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private FileService service;

    @BeforeEach
    void setUp() {
        StoredFileJpaRepository repo = mock(StoredFileJpaRepository.class);
        when(repo.save(any(StoredFile.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new FileService(repo, mock(StorageService.class));
    }

    private StoredFile scan(String name, byte[] bytes) {
        return service.store(practice, FileOwnerType.PATIENT_DOCUMENT, patient,
                new MockMultipartFile("file", name, "application/octet-stream", bytes), null, DocumentCategory.INTRAORAL_3D);
    }

    static byte[] binaryStl(int triangles) {
        ByteBuffer b = ByteBuffer.allocate(84 + 50 * triangles).order(ByteOrder.LITTLE_ENDIAN);
        b.put(new byte[80]).putInt(triangles);
        return b.array();
    }

    @Test
    void keepsTheCategoryOfAPatientDocument() {
        StoredFile f = service.store(practice, FileOwnerType.PATIENT_DOCUMENT, patient,
                new MockMultipartFile("file", "pano.png", "image/png", PNG), null, DocumentCategory.XRAY);
        assertThat(f.getCategory()).isEqualTo(DocumentCategory.XRAY);
        assertThat(f.getContentType()).isEqualTo("image/png");
    }

    @Test
    void onlyAPatientDocumentHasACategory() {
        assertThatThrownBy(() -> service.store(practice, FileOwnerType.EXPENSE_RECEIPT, UUID.randomUUID(),
                new MockMultipartFile("file", "a.png", "image/png", PNG), null, DocumentCategory.IMAGE))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void anIntraoralScanMayBeA3dModel() {
        assertThat(scan("upper.stl", binaryStl(3)).getContentType()).isEqualTo("model/stl");
        assertThat(scan("lower.stl", "solid jaw\n facet normal 0 0 1\n outer loop\n".getBytes(StandardCharsets.US_ASCII)).getContentType())
                .isEqualTo("model/stl");
        assertThat(scan("arch.ply", "ply\nformat binary_little_endian 1.0\n".getBytes(StandardCharsets.US_ASCII)).getContentType())
                .isEqualTo("model/x-ply");
        assertThat(scan("arch.obj", "# scan\nv 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n".getBytes(StandardCharsets.US_ASCII)).getContentType())
                .isEqualTo("model/obj");
        assertThat(scan("arch.glb", new byte[]{'g', 'l', 'T', 'F', 2, 0, 0, 0}).getContentType()).isEqualTo("model/gltf-binary");
        assertThat(scan("arch.gltf", "{\"asset\":{\"version\":\"2.0\"}}".getBytes(StandardCharsets.US_ASCII)).getContentType())
                .isEqualTo("model/gltf+json");
        assertThat(scan("arch.3mf", threeMf()).getContentType()).isEqualTo("model/3mf");
        assertThat(scan("photo.png", PNG).getContentType()).isEqualTo("image/png");
    }

    @Test
    void anXrayIsNeverA3dModel() {
        assertThatThrownBy(() -> service.store(practice, FileOwnerType.PATIENT_DOCUMENT, patient,
                new MockMultipartFile("file", "upper.stl", "model/stl", binaryStl(3)), null, DocumentCategory.XRAY))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void textThatIsNotAModelIsRefused() {
        assertThatThrownBy(() -> scan("page.obj", "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> scan("page.gltf", "<html>{\"asset\":1}</html>".getBytes(StandardCharsets.US_ASCII)))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void aModelMayBeLargerThanAPicture() {
        byte[] big = binaryStl(400_000); // about 20 MB, over the 15 MB of a picture
        assertThat(scan("full-arch.stl", big).getSizeBytes()).isEqualTo(big.length);
        byte[] bigPng = new byte[16 * 1024 * 1024];
        System.arraycopy(PNG, 0, bigPng, 0, PNG.length);
        assertThatThrownBy(() -> scan("huge.png", bigPng)).isInstanceOf(ValidationException.class);
    }

    private static byte[] threeMf() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
                zip.write("<Types/>".getBytes(StandardCharsets.US_ASCII));
                zip.putNextEntry(new ZipEntry("3D/3dmodel.model"));
                zip.write("<model/>".getBytes(StandardCharsets.US_ASCII));
            }
            return out.toByteArray();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
