package com.orthoflow.storage.presentation.controller;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Authenticated file access. Files are never served from a public path; each
 * request is checked against the permission of the record the file belongs to.
 */
@RestController
@RequestMapping("/files")
@RequiredArgsConstructor
public class FileController {

    public record FileInfo(UUID id, FileOwnerType ownerType, UUID ownerId, String name, String contentType,
                           long sizeBytes, OffsetDateTime createdAt) {
        static FileInfo from(StoredFile f) {
            return new FileInfo(f.getId(), f.getOwnerType(), f.getOwnerId(), f.getOriginalName(), f.getContentType(),
                    f.getSizeBytes(), f.getCreatedAt());
        }
    }

    private final FileService fileService;
    private final CurrentUserProvider currentUser;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public FileInfo upload(@RequestParam FileOwnerType ownerType, @RequestParam(required = false) UUID ownerId,
                           @RequestPart("file") MultipartFile file) {
        require(ownerType.writePermission().name());
        return FileInfo.from(fileService.store(currentUser.requirePracticeId(), ownerType, ownerId, file,
                currentUser.requireUserId()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id) {
        StoredFile file = fileService.require(currentUser.requirePracticeId(), id);
        require(file.getOwnerType().readPermission().name());
        boolean inline = file.getContentType().startsWith("image/") || file.getContentType().equals("application/pdf");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
                        .filename(file.getOriginalName(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .contentType(MediaType.parseMediaType(file.getContentType()))
                .contentLength(file.getSizeBytes())
                .body(new InputStreamResource(fileService.open(file)));
    }

    @GetMapping
    public java.util.List<FileInfo> list(@RequestParam FileOwnerType ownerType, @RequestParam UUID ownerId) {
        require(ownerType.readPermission().name());
        return fileService.forOwner(currentUser.requirePracticeId(), ownerType, ownerId).stream().map(FileInfo::from).toList();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        StoredFile file = fileService.require(currentUser.requirePracticeId(), id);
        require(file.getOwnerType().writePermission().name());
        fileService.delete(currentUser.requirePracticeId(), id);
    }

    private void require(String permission) {
        currentUser.requireAuthority(permission);
    }
}
