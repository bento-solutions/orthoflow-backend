package com.orthoflow.messaging.application.service;

import com.orthoflow.common.events.LiveEventPublisher;
import com.orthoflow.common.exception.ConflictException;
import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.common.exception.ValidationException;
import com.orthoflow.export.application.dto.Letterhead;
import com.orthoflow.export.application.port.LetterheadProvider;
import com.orthoflow.messaging.application.dto.MessagingDtos.LogRow;
import com.orthoflow.messaging.application.dto.OutgoingMessage;
import com.orthoflow.messaging.domain.model.*;
import com.orthoflow.messaging.infrastructure.persistence.OutboxJpaRepository;
import com.orthoflow.patient.application.port.PatientLookup;
import com.orthoflow.patient.application.port.PatientSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The only way a message leaves the application: producers call
 * {@link #enqueue}, which writes a row inside their own transaction. Nothing
 * sends inline, so a provider outage never fails the business action that
 * triggered the message (an appointment is still booked when WhatsApp is down).
 */
@Service
@RequiredArgsConstructor
public class MessageService {

    private final OutboxJpaRepository outbox;
    private final MessageTemplateService templates;
    private final ConsentService consent;
    private final PatientLookup patientLookup;
    private final LetterheadProvider letterheadProvider;
    private final LiveEventPublisher liveEvents;

    /**
     * Queues a message and returns it. An enqueue whose {@code dedupeKey} was
     * already used returns the earlier message untouched. A patient-directed
     * message with no recorded opt-in is stored CANCELLED with that reason, so
     * staff can see why a reminder did not go instead of wondering.
     */
    @Transactional
    public Optional<OutboxMessage> enqueue(OutgoingMessage request) {
        if (request.getDedupeKey() != null) {
            Optional<OutboxMessage> existing = outbox.findByDedupeKey(request.getDedupeKey());
            if (existing.isPresent()) {
                return existing;
            }
        }
        if (request.getChannel() == MessageChannel.IN_APP) {
            return Optional.of(enqueueInApp(request));
        }

        PatientSummary patient = request.getPatientId() == null ? null
                : patientLookup.findSummary(request.getPatientId()).orElse(null);
        String recipient = request.getRecipient() != null ? request.getRecipient()
                : patient == null ? null
                : request.getChannel() == MessageChannel.EMAIL ? patient.email() : patient.phone();
        String language = request.getLanguage() == null ? "fr" : request.getLanguage();

        String subject = request.getSubject();
        String body = request.getBody();
        if (body == null) {
            var text = templates.resolve(request.getPracticeId(), request.getChannel(), request.getPurpose(), language);
            if (text.isEmpty()) {
                throw new ValidationException("No " + request.getChannel() + " template for " + request.getPurpose()
                        + " in '" + language + "'");
            }
            Map<String, String> variables = variablesFor(request, patient);
            subject = TemplateRenderer.render(text.get().subject(), variables);
            body = TemplateRenderer.render(text.get().body(), variables);
        }

        OutboxMessage message = OutboxMessage.builder()
                .practiceId(request.getPracticeId())
                .channel(request.getChannel())
                .purpose(request.getPurpose())
                .recipient(recipient)
                .patientId(request.getPatientId())
                .language(language)
                .subject(subject)
                .body(body)
                .relatedType(request.getRelatedType())
                .relatedId(request.getRelatedId())
                .dedupeKey(request.getDedupeKey())
                .scheduledFor(request.getScheduledFor())
                .createdBy(request.getCreatedBy())
                .build();

        if (!request.isSkipConsentCheck() && request.getPatientId() != null
                && !consent.isOptedIn(request.getPatientId(), request.getChannel())) {
            message.setStatus(MessageStatus.CANCELLED);
            message.setLastError("The patient has not agreed to receive " + request.getChannel() + " messages");
        } else if (recipient == null || recipient.isBlank()) {
            message.setStatus(MessageStatus.CANCELLED);
            message.setLastError("No " + (request.getChannel() == MessageChannel.EMAIL ? "email address" : "phone number") + " on file");
        }
        return Optional.of(outbox.save(message));
    }

    /** Whether a message with this dedupe key already exists, queued or sent: lets a job count only what is new. */
    @Transactional(readOnly = true)
    public boolean alreadyQueued(String dedupeKey) {
        return outbox.existsByDedupeKey(dedupeKey);
    }

    private OutboxMessage enqueueInApp(OutgoingMessage request) {
        if (request.getRecipientUserId() == null) {
            throw new ValidationException("An in-app message needs a recipient user");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OutboxMessage saved = outbox.save(OutboxMessage.builder()
                .practiceId(request.getPracticeId())
                .channel(MessageChannel.IN_APP)
                .purpose(request.getPurpose())
                .status(MessageStatus.SENT)
                .recipientUserId(request.getRecipientUserId())
                .language(request.getLanguage() == null ? "fr" : request.getLanguage())
                .subject(request.getSubject())
                .body(request.getBody())
                .relatedType(request.getRelatedType())
                .relatedId(request.getRelatedId())
                .dedupeKey(request.getDedupeKey())
                .sentAt(now)
                .createdBy(request.getCreatedBy())
                .build());
        liveEvents.publish(request.getPracticeId(), "notification", request.getRecipientUserId());
        return saved;
    }

    private Map<String, String> variablesFor(OutgoingMessage request, PatientSummary patient) {
        Map<String, String> variables = new HashMap<>();
        Letterhead clinic = letterheadProvider.forPractice(request.getPracticeId());
        variables.put("clinicName", clinic.name());
        variables.put("clinicPhone", clinic.phone() == null ? "" : clinic.phone());
        if (patient != null) {
            variables.put("patientName", patient.fullName());
            variables.put("firstName", patient.firstName());
        }
        if (request.getVariables() != null) {
            variables.putAll(request.getVariables());
        }
        return variables;
    }

    @Transactional(readOnly = true)
    public Page<LogRow> logs(UUID practiceId, MessageChannel channel, MessageStatus status, UUID patientId, int page, int size) {
        return outbox.search(practiceId, channel, status, patientId, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)))
                .map(LogRow::from);
    }

    /** Puts a failed or cancelled message back in the queue for another go. */
    @Transactional
    public LogRow retry(UUID practiceId, UUID id) {
        OutboxMessage m = require(practiceId, id);
        if (m.getStatus() != MessageStatus.FAILED && m.getStatus() != MessageStatus.CANCELLED) {
            throw new ConflictException("Only a failed or cancelled message can be retried");
        }
        if (m.getBody() == null) {
            throw new ConflictException("The text of this message has been purged and it cannot be resent");
        }
        m.setStatus(MessageStatus.QUEUED);
        m.setAttempts(0);
        m.setLastError(null);
        m.setNextAttemptAt(OffsetDateTime.now());
        return LogRow.from(m);
    }

    @Transactional
    public LogRow cancel(UUID practiceId, UUID id) {
        OutboxMessage m = require(practiceId, id);
        if (m.getStatus() != MessageStatus.QUEUED) {
            throw new ConflictException("Only a message still waiting to be sent can be cancelled");
        }
        m.setStatus(MessageStatus.CANCELLED);
        m.setLastError("Cancelled by staff");
        return LogRow.from(m);
    }

    private OutboxMessage require(UUID practiceId, UUID id) {
        return outbox.findByIdAndPracticeId(id, practiceId).orElseThrow(() -> new NotFoundException("Message not found"));
    }
}
