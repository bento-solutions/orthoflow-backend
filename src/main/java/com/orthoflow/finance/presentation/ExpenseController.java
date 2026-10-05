package com.orthoflow.finance.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.export.application.service.ExportService;
import com.orthoflow.finance.application.dto.ExpenseDtos.*;
import com.orthoflow.finance.application.service.ExpenseService;
import com.orthoflow.finance.domain.model.Expense;
import com.orthoflow.storage.application.service.FileService;
import com.orthoflow.storage.domain.model.FileOwnerType;
import com.orthoflow.storage.domain.model.StoredFile;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/finance/expenses")
@RequiredArgsConstructor
public class ExpenseController {

    private final ExpenseService service;
    private final FileService fileService;
    private final ExportService exportService;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public List<View> list(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                           @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                           @RequestParam(required = false) UUID categoryId,
                           @RequestParam(required = false) Expense.Status status,
                           @RequestParam(required = false) String search) {
        return service.list(currentUser.requirePracticeId(), from, to, categoryId, status, search);
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                         @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                         @RequestParam(required = false) UUID categoryId,
                                         @RequestParam(required = false) Expense.Status status,
                                         @RequestParam(defaultValue = "xlsx") String format,
                                         @RequestParam(defaultValue = "fr") String lang) {
        UUID practice = currentUser.requirePracticeId();
        return exportService.respond(service.table(service.list(practice, from, to, categoryId, status, null), lang, from, to),
                format, "depenses-" + from + "-" + to, practice, lang);
    }

    @GetMapping("/{id}")
    public View get(@PathVariable UUID id) {
        return service.get(currentUser.requirePracticeId(), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public View create(@Valid @RequestBody Request request) {
        return service.create(currentUser.requirePracticeId(), currentUser.requireUserId(), request);
    }

    @PutMapping("/{id}")
    public View update(@PathVariable UUID id, @Valid @RequestBody Request request) {
        return service.update(currentUser.requirePracticeId(), id, request);
    }

    @PostMapping("/{id}/pay")
    public View pay(@PathVariable UUID id, @Valid @RequestBody MarkPaid request) {
        return service.markPaid(currentUser.requirePracticeId(), id, request);
    }

    @PostMapping("/{id}/cancel")
    public View cancel(@PathVariable UUID id) {
        return service.cancel(currentUser.requirePracticeId(), id);
    }

    @PostMapping(value = "/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, UUID> attachReceipt(@PathVariable UUID id, @RequestPart("file") MultipartFile file) {
        UUID practice = currentUser.requirePracticeId();
        service.get(practice, id);
        StoredFile stored = fileService.store(practice, FileOwnerType.EXPENSE_RECEIPT, id, file, currentUser.requireUserId());
        service.attachReceipt(practice, id, stored.getId());
        return Map.of("receiptFileId", stored.getId());
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return service.categories(currentUser.requirePracticeId());
    }

    @PostMapping("/categories")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryView createCategory(@Valid @RequestBody CategoryRequest request) {
        return service.createCategory(currentUser.requirePracticeId(), request);
    }

    @PutMapping("/categories/{id}")
    public CategoryView updateCategory(@PathVariable UUID id, @Valid @RequestBody CategoryRequest request) {
        return service.updateCategory(currentUser.requirePracticeId(), id, request);
    }
}
