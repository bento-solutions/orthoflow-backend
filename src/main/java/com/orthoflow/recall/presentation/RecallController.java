package com.orthoflow.recall.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.export.application.service.ExportService;
import com.orthoflow.recall.application.dto.RecallDtos.Filter;
import com.orthoflow.recall.application.dto.RecallDtos.Kind;
import com.orthoflow.recall.application.dto.RecallDtos.Row;
import com.orthoflow.recall.application.service.RecallService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Who to call: lapsed patients, nothing booked, lost mid-treatment, retention checks due. Queries only; reminders come with messaging. */
@RestController
@RequestMapping("/recalls")
@RequiredArgsConstructor
public class RecallController {

    private final RecallService service;
    private final ExportService exportService;
    private final CurrentUserProvider currentUser;

    @GetMapping("/{kind}")
    public List<Row> list(@PathVariable Kind kind,
                          @RequestParam(required = false) UUID practitionerId,
                          @RequestParam(required = false) Integer minProgress,
                          @RequestParam(required = false) Integer maxProgress,
                          @RequestParam(defaultValue = "true") boolean excludeNeverVisited,
                          @RequestParam(defaultValue = "last") String sort) {
        return service.list(filter(kind, practitionerId, minProgress, maxProgress, excludeNeverVisited, sort));
    }

    /** The same list as a file: {@code ?format=csv|xlsx|pdf}. */
    @GetMapping("/{kind}/export")
    public ResponseEntity<byte[]> export(@PathVariable Kind kind,
                                         @RequestParam(required = false) UUID practitionerId,
                                         @RequestParam(required = false) Integer minProgress,
                                         @RequestParam(required = false) Integer maxProgress,
                                         @RequestParam(defaultValue = "true") boolean excludeNeverVisited,
                                         @RequestParam(defaultValue = "last") String sort,
                                         @RequestParam(defaultValue = "csv") String format,
                                         @RequestParam(defaultValue = "fr") String lang) {
        Filter filter = filter(kind, practitionerId, minProgress, maxProgress, excludeNeverVisited, sort);
        return exportService.respond(service.table(filter, service.list(filter)), format, "rappels-" + kind.name().toLowerCase(),
                currentUser.requirePracticeId(), lang);
    }

    private Filter filter(Kind kind, UUID practitionerId, Integer minProgress, Integer maxProgress, boolean excludeNeverVisited, String sort) {
        return new Filter(currentUser.requirePracticeId(), kind, practitionerId, minProgress, maxProgress, excludeNeverVisited, sort);
    }
}
