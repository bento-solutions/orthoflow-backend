package com.orthoflow.messaging.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.IntNode;
import com.orthoflow.messaging.domain.model.OutboxMessage;
import com.orthoflow.messaging.infrastructure.MessagingProperties;
import com.orthoflow.messaging.infrastructure.persistence.OutboxJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The bridge's side of a batch: it retries exactly the ids it is told failed, matching them by
 * value. Its ids are numbers, so they must come back as numbers, or a failed event would be
 * taken as done and lost.
 */
class WhatsAppWebhookBatchTest {

    @Test
    void aFailedEventIsReportedWithTheIdTheBridgeSentAndEventsOfOtherSessionsAreLeftAlone() {
        MessagingProperties props = new MessagingProperties();
        props.getWhatsapp().setSessionId("bento-crm-main");
        OutboxJpaRepository outbox = mock(OutboxJpaRepository.class);
        when(outbox.findByProviderMessageId("BROKEN")).thenThrow(new IllegalStateException("database down"));
        when(outbox.findByProviderMessageId("OK")).thenReturn(java.util.Optional.<OutboxMessage>empty());
        TransactionTemplate tx = mock(TransactionTemplate.class);
        doAnswer(inv -> {
            inv.<Consumer<org.springframework.transaction.TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(tx).executeWithoutResult(any());
        WhatsAppWebhookService service = new WhatsAppWebhookService(props, new ObjectMapper(), outbox, null, null, null, null, null, null, tx);

        var failed = service.handle("""
                {"events":[
                  {"id":7,"sessionId":"bento-crm-main","type":"message.status","data":{"wamid":"BROKEN","status":"READ"}},
                  {"id":8,"sessionId":"bento-crm-main","type":"message.status","data":{"wamid":"OK","status":"READ"}},
                  {"id":9,"sessionId":"another-account","type":"message.status","data":{"wamid":"THEIRS","status":"READ"}}]}
                """);

        assertThat(failed).containsExactly(IntNode.valueOf(7));
        verify(outbox).findByProviderMessageId(eq("OK"));
        verify(outbox, never()).findByProviderMessageId(eq("THEIRS"));
    }
}
