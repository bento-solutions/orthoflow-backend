package com.orthoflow.storage.application.service;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.storage.application.port.StorageService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import com.orthoflow.storage.infrastructure.StoredFileJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileService {

    private final StoredFileJpaRepository files;
    private final StorageService storage;

    /**
     * Stores an upload. The type is decided from the bytes, never from what the
     * client claims: a page that serves a "photo" as {@code text/html} is a
     * stored cross-site scripting hole.
     */
    @Transactional
    public StoredFile store(UUID practiceId, FileOwnerType ownerType, UUID ownerId, MultipartFile upload, UUID actorId) {
        if (upload == null || upload.isEmpty()) {
            throw new ValidationException("The file is empty");
        }
        if (upload.getSize() > ownerType.maxBytes()) {
            throw new ValidationException("The file is larger than " + ownerType.maxBytes() / (1024 * 1024) + " MB");
        }
        byte[] bytes;
        try {
            bytes = upload.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        String contentType = sniff(bytes);
        UUID id = UUID.randomUUID();
        String key = practiceId + "/" + ownerType.name().toLowerCase() + "/" + id;
        try {
            storage.put(key, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
        return files.save(StoredFile.builder().id(id).practiceId(practiceId).ownerType(ownerType).ownerId(ownerId)
                .originalName(cleanName(name)).contentType(contentType).sizeBytes(bytes.length)
                .sha256(sha256(bytes)).storageKey(key).uploadedBy(actorId).build());
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

    /** A soft delete: the row stays for the record, the bytes go. */
    @Transactional
    public void delete(UUID practiceId, UUID id) {
        StoredFile file = require(practiceId, id);
        file.setDeletedAt(OffsetDateTime.now());
        try {
            storage.delete(file.getStorageKey());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
