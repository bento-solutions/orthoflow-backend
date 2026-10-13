package com.orthoflow.audit.presentation;

import com.orthoflow.audit.application.service.AuditCategory;
import com.orthoflow.audit.application.service.AuditQueryService;
import com.orthoflow.audit.application.service.AuditQueryService.Filter;
import com.orthoflow.audit.application.service.AuditQueryService.PageOfRows;
import com.orthoflow.common.security.CurrentUserProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Settings → Audit log: who signed in, who changed what, who looked at which patient. Administrators only. */
@RestController
@RequestMapping("/admin/audit-log")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('USERS_MANAGE')")
public class AuditLogController {

    private final AuditQueryService queries;
    private final CurrentUserProvider currentUser;

    @GetMapping
    public PageOfRows search(@RequestParam(required = false) AuditCategory category,
                             @RequestParam(required = false) UUID patientId,
                             @RequestParam(required = false) UUID actorId,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "50") int size) {
        return queries.search(currentUser.requirePracticeId(), new Filter(category, patientId, actorId, from, to), page, size);
    }
}
