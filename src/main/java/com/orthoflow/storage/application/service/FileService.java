package com.orthoflow.storage.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.storage.application.port.StorageService;
import com.orthoflow.storage.domain.model.DocumentCategory;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import com.orthoflow.storage.infrastructure.StoredFileJpaRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    private final StoredFileJpaRepository files;
    private final StorageService storage;

    /**
     * Stores an upload. The type is decided from the bytes, never from what the
     * client claims: a page that serves a "photo" as {@code text/html} is a
     * stored cross-site scripting hole.
     */
    @Transactional
    public StoredFile store(UUID practiceId, FileOwnerType ownerType, UUID ownerId, MultipartFile upload, UUID actorId) {
        return store(practiceId, ownerType, ownerId, upload, actorId, null);
    }

    /** As {@link #store}, recording what a patient document is. Only patient documents take a category. */
    @Transactional
    public StoredFile store(UUID practiceId, FileOwnerType ownerType, UUID ownerId, MultipartFile upload, UUID actorId,
                            DocumentCategory category) {
        if (category != null && ownerType != FileOwnerType.PATIENT_DOCUMENT) {
            throw new ValidationException("Only a patient document takes a category");
        }
        if (upload == null || upload.isEmpty()) {
            throw new ValidationException("The file is empty");
        }
        // An intraoral scan may be a 3D model, and a full-arch one runs to tens of megabytes.
        boolean mayBeModel = category == DocumentCategory.INTRAORAL_3D;
        long cap = mayBeModel ? Math.max(MODEL_MAX_BYTES, ownerType.maxBytes()) : ownerType.maxBytes();
        if (upload.getSize() > cap) {
            throw new ValidationException("The file is larger than " + cap / (1024 * 1024) + " MB");
        }
        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String model = mayBeModel ? sniffModel(bytes, upload.getOriginalFilename()) : null;
        String contentType = model != null ? model : sniff(bytes);
        if (model == null && bytes.length > ownerType.maxBytes()) {
            throw new ValidationException("The file is larger than " + ownerType.maxBytes() / (1024 * 1024) + " MB");
        }
        UUID id = UUID.randomUUID();
        String key = practiceId + "/" + ownerType.name().toLowerCase() + "/" + id;
        try {
            storage.put(key, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        removeIfRolledBack(key);
        return files.save(StoredFile.builder()
                .id(id)
                .practiceId(practiceId)
                .ownerType(ownerType)
                .ownerId(ownerId)
                .originalName(cleanName(upload.getOriginalFilename()))
                .contentType(contentType)
                .sizeBytes(bytes.length)
                .sha256(sha256(bytes))
                .storageKey(key)
                .category(category)
                .uploadedBy(actorId)
                .build());
    }

    /** Stores bytes the server produced itself (a generated PDF kept for the record). */
    @Transactional
    public StoredFile storeGenerated(UUID practiceId, FileOwnerType ownerType, UUID ownerId, String name,
                                     String contentType, byte[] bytes, UUID actorId) {
        UUID id = UUID.randomUUID();
        String key = practiceId + "/" + ownerType.name().toLowerCase() + "/" + id;
        try {
            storage.put(key, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        removeIfRolledBack(key);
        return files.save(StoredFile.builder().id(id).practiceId(practiceId).ownerType(ownerType).ownerId(ownerId)
                .originalName(cleanName(name)).contentType(contentType).sizeBytes(bytes.length)
                .sha256(sha256(bytes)).storageKey(key).uploadedBy(actorId).build());
    }

    /**
     * Stores a recording. Its type is decided from the bytes, like an upload's, but only audio is
     * accepted — and only here: the generic upload never takes audio, so a file served as audio is
     * always one a consultation recorded.
     */
    @Transactional
    public StoredFile storeAudio(UUID practiceId, FileOwnerType ownerType, UUID ownerId, String name,
                                 byte[] bytes, long maxBytes, UUID actorId) {
        if (bytes == null || bytes.length == 0) {
            throw new ValidationException("The recording is empty");
        }
        long cap = Math.min(maxBytes, ownerType.maxBytes());
        if (bytes.length > cap) {
            throw new ValidationException("The recording is larger than " + cap / (1024 * 1024) + " MB");
        }
        return storeGenerated(practiceId, ownerType, ownerId, name, sniffAudio(bytes), bytes, actorId);
    }

    @Transactional(readOnly = true)
    public StoredFile require(UUID practiceId, UUID id) {
        return files.findByIdAndPracticeIdAndDeletedAtIsNull(id, practiceId)
                .orElseThrow(() -> new NotFoundException("File not found"));
    }

    @Transactional(readOnly = true)
    public List<StoredFile> forOwner(UUID practiceId, FileOwnerType ownerType, UUID ownerId) {
        return files.findByPracticeIdAndOwnerTypeAndOwnerIdAndDeletedAtIsNullOrderByCreatedAtDesc(practiceId, ownerType, ownerId);
    }

    public InputStream open(StoredFile file) {
        try {
            return storage.open(file.getStorageKey());
        } catch (IOException e) {
            throw new NotFoundException("File content is not available");
        }
    }

    public byte[] read(StoredFile file) {
        try (InputStream in = open(file)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A soft delete: the row stays for the record, the bytes go once that is committed. */
    @Transactional
    public void delete(UUID practiceId, UUID id) {
        StoredFile file = require(practiceId, id);
        file.setDeletedAt(OffsetDateTime.now());
        deleteBytesAfterCommit(List.of(file.getStorageKey()));
    }

    /**
     * Deletes stored bytes once the transaction that removed (or soft-deleted) their rows has
     * committed. Deleting them inside it lost a patient's X-rays whenever the transaction then
     * rolled back: the rows came back, the files did not. Outside a transaction they go at once.
     * A file that cannot be deleted is logged for an operator; its row is already gone.
     */
    public void deleteBytesAfterCommit(Collection<String> storageKeys) {
        List<String> keys = List.copyOf(storageKeys);
        if (keys.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteBytes(keys);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                deleteBytes(keys);
            }
        });
    }

    private void deleteBytes(List<String> keys) {
        for (String key : keys) {
            try {
                storage.delete(key);
            } catch (IOException | RuntimeException e) {
                log.error("Could not delete the stored file {}; its row is gone, so it needs removing by hand", key, e);
            }
        }
    }

    /** Bytes written for a row that then never commits are removed, so a failed save leaves no orphan file behind. */
    private void removeIfRolledBack(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    deleteBytes(List.of(key));
                }
            }
        });
    }

    /** The type of an upload decided from its first bytes; refuses anything that is not PNG, JPEG, WebP or PDF. */
    public static String sniff(byte[] b) {
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (b.length >= 5 && b[0] == '%' && b[1] == 'P' && b[2] == 'D' && b[3] == 'F' && b[4] == '-') return "application/pdf";
        throw new ValidationException("Only PNG, JPEG, WebP and PDF files can be uploaded");
    }

    /** The largest 3D model an intraoral scan may be: a full-arch STL is commonly 20 to 60 MB. */
    public static final long MODEL_MAX_BYTES = 100L * 1024 * 1024;

    /**
     * The type of a 3D model from its bytes: STL (binary or ASCII), PLY, OBJ, 3MF, and glTF
     * (binary .glb, or .gltf with its buffers embedded). Null when the bytes are none of these.
     * The models are served as attachments with nosniff, never inline, and the browser only
     * ever reads them as data for the viewer.
     */
    public static String sniffModel(byte[] b, String name) {
        if (b.length >= 4 && b[0] == 'g' && b[1] == 'l' && b[2] == 'T' && b[3] == 'F') return "model/gltf-binary";
        if (startsWith(b, "ply\n") || startsWith(b, "ply\r\n")) return "model/x-ply";
        if (b.length >= 84) {
            // Binary STL: an 80-byte header, a little-endian triangle count, 50 bytes per triangle.
            long triangles = (b[80] & 0xFFL) | (b[81] & 0xFFL) << 8 | (b[82] & 0xFFL) << 16 | (b[83] & 0xFFL) << 24;
            // Some exporters pad the end; a few hundred bytes over is still the same file.
            long expected = 84 + 50 * triangles;
            if (triangles > 0 && b.length >= expected && b.length - expected < 512) return "model/stl";
        }
        if (b.length >= 4 && b[0] == 'P' && b[1] == 'K' && b[2] == 3 && b[3] == 4 && isThreeMf(b)) return "model/3mf";
        String head = asciiHead(b, 4096);
        if (head == null) return null;
        String trimmed = head.stripLeading();
        if (trimmed.startsWith("solid") && head.contains("facet")) return "model/stl";
        if (trimmed.startsWith("{") && head.contains("\"asset\"") && lowerName(name).endsWith(".gltf")) return "model/gltf+json";
        if (lowerName(name).endsWith(".obj") && looksLikeObj(head)) return "model/obj";
        return null;
    }

    private static boolean startsWith(byte[] b, String prefix) {
        if (b.length < prefix.length()) return false;
        for (int i = 0; i < prefix.length(); i++) if (b[i] != prefix.charAt(i)) return false;
        return true;
    }

    private static String lowerName(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }

    /** The first bytes as text, or null when they hold anything but printable ASCII and whitespace. */
    private static String asciiHead(byte[] b, int max) {
        int n = Math.min(b.length, max);
        for (int i = 0; i < n; i++) {
            int c = b[i] & 0xFF;
            if (c >= 0x7F || (c < 0x20 && c != '\n' && c != '\r' && c != '\t')) return null;
        }
        return new String(b, 0, n, java.nio.charset.StandardCharsets.US_ASCII);
    }

    /** OBJ is plain text whose every statement starts with one of a few keywords. */
    private static boolean looksLikeObj(String head) {
        boolean vertex = false;
        for (String line : head.split("\r?\n")) {
            String l = line.strip();
            if (l.isEmpty() || l.startsWith("#")) continue;
            String keyword = l.split("\\s+", 2)[0];
            if (keyword.equals("v")) vertex = true;
            else if (!java.util.Set.of("vn", "vt", "vp", "f", "l", "p", "o", "g", "s", "mtllib", "usemtl").contains(keyword)) {
                // The last line of the head may be cut mid-statement.
                if (head.endsWith(line)) break;
                return false;
            }
        }
        return vertex;
    }

    /** A 3MF is a zip whose model part is 3D/3dmodel.model. */
    private static boolean isThreeMf(byte[] b) {
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(b))) {
            for (java.util.zip.ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                if (e.getName().equalsIgnoreCase("3D/3dmodel.model")) return true;
            }
        } catch (IOException | RuntimeException e) {
            return false;
        }
        return false;
    }

    /**
     * The type of a recording from its first bytes: WebM (Chrome, Firefox, Edge), Ogg, MP4 (Safari)
     * or WAV. Anything else is refused.
     */
    public static String sniffAudio(byte[] b) {
        if (b.length >= 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3) {
            return "audio/webm";
        }
        if (b.length >= 4 && b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return "audio/ogg";
        if (b.length >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return "audio/mp4";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'A' && b[10] == 'V' && b[11] == 'E') return "audio/wav";
        throw new ValidationException("Only WebM, Ogg, MP4 and WAV recordings can be kept");
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "file" : raw.replaceAll("[\\\\/\\r\\n\\x00]", "_").trim();
        return name.isEmpty() ? "file" : name.length() > 255 ? name.substring(0, 255) : name;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
