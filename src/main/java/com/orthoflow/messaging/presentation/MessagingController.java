package com.orthoflow.messaging.presentation;

import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.messaging.application.dto.MessagingDtos.*;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.application.service.*;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.messaging.domain.model.MessageStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/** Message log, templates, the WhatsApp reply inbox and per-patient consent. Authorities are set in SecurityConfig. */
@RestController
@RequiredArgsConstructor
public class MessagingController {

    private final MessageService messageService;
    private final MessageTemplateService templateService;
    private final WhatsAppWebhookService webhookService;
    private final ConsentService consentService;
    private final CurrentUserProvider currentUser;

    @GetMapping("/messaging/logs")
    public Page<LogRow> logs(@RequestParam(required = false) MessageChannel channel,
                             @RequestParam(required = false) MessageStatus status,
                             @RequestParam(required = false) UUID patientId,
                             @RequestParam(defaultValue = "0") int page,
                             @RequestParam(defaultValue = "25") int size) {
        return messageService.logs(currentUser.requirePracticeId(), channel, status, patientId, page, size);
    }

    @PostMapping("/messaging/logs/{id}/retry")
    public LogRow retry(@PathVariable UUID id) {
        return messageService.retry(currentUser.requirePracticeId(), id);
    }

    @PostMapping("/messaging/logs/{id}/cancel")
    public LogRow cancel(@PathVariable UUID id) {
        return messageService.cancel(currentUser.requirePracticeId(), id);
    }

    @GetMapping("/messaging/templates")
    public List<TemplateRow> templates() {
        return templateService.list(currentUser.requirePracticeId());
    }

    @PutMapping("/messaging/templates")
    public TemplateRow upsertTemplate(@Valid @RequestBody TemplateUpsert request) {
        return templateService.upsert(currentUser.requirePracticeId(), request);
    }

    @DeleteMapping("/messaging/templates/{purpose}/{channel}/{language}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revertTemplate(@PathVariable MessagePurpose purpose, @PathVariable MessageChannel channel,
                               @PathVariable String language) {
        templateService.revert(currentUser.requirePracticeId(), purpose, channel, language);
    }

    @PostMapping("/messaging/templates/preview")
    public Preview preview(@Valid @RequestBody PreviewRequest request) {
        return templateService.preview(request);
    }

    /** Queues a test message to an address typed by the admin, to prove a channel works end to end. */
    @PostMapping("/messaging/send-test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public LogRow sendTest(@Valid @RequestBody SendTest request) {
        return messageService.enqueue(OutgoingMessage.builder()
                        .practiceId(currentUser.requirePracticeId())
                        .channel(request.channel())
                        .purpose(MessagePurpose.TEST)
                        .recipient(request.recipient())
                        .createdBy(currentUser.requireUserId())
                        .skipConsentCheck(true)
                        .build())
                .map(LogRow::from).orElseThrow();
    }

    @GetMapping("/messaging/inbox")
    public List<InboxRow> inbox(@RequestParam(defaultValue = "true") boolean unhandledOnly,
                                @RequestParam(defaultValue = "false") boolean landingPageOnly,
                                @RequestParam(defaultValue = "50") int limit) {
        return webhookService.inbox(currentUser.requirePracticeId(), unhandledOnly, landingPageOnly, limit);
    }

    @PostMapping("/messaging/inbox/{id}/handled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markHandled(@PathVariable UUID id) {
        webhookService.markHandled(currentUser.requirePracticeId(), id);
    }

    @GetMapping("/messaging/whatsapp/status")
    public WhatsAppStatus whatsappStatus() {
        return webhookService.status(currentUser.requirePracticeId());
    }

    @GetMapping("/patients/{patientId}/consent")
    public Consent consent(@PathVariable UUID patientId) {
        return new Consent(consentService.forPatient(patientId));
    }

    @PutMapping("/patients/{patientId}/consent")
    public Consent setConsent(@PathVariable UUID patientId, @Valid @RequestBody SetConsent request) {
        consentService.record(patientId, request.channel(), request.optedIn(), "STAFF");
        return new Consent(consentService.forPatient(patientId));
    }
}
