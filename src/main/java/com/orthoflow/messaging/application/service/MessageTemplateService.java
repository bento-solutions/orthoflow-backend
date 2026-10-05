package com.orthoflow.messaging.application.service;

import com.orthoflow.messaging.application.dto.MessagingDtos.*;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.messaging.domain.model.MessagePurpose;
import com.orthoflow.messaging.domain.model.MessageTemplate;
import com.orthoflow.messaging.infrastructure.persistence.MessageTemplateJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MessageTemplateService {

    private static final List<String> LANGUAGES = List.of("fr", "en", "ar");
    private static final List<MessageChannel> CHANNELS = List.of(MessageChannel.WHATSAPP, MessageChannel.EMAIL);

    /** Sample values for the preview, so staff see what a patient would. */
    private static final Map<String, String> SAMPLE = Map.ofEntries(
            Map.entry("patientName", "Sara Benziane"), Map.entry("date", "12/11/2026"), Map.entry("time", "10:30"),
            Map.entry("clinicName", "Cabinet"), Map.entry("clinicPhone", "+212 5 22 00 00 00"),
            Map.entry("amount", "1 500,00"), Map.entry("currency", "MAD"), Map.entry("link", "https://…"));

    private final MessageTemplateJpaRepository templates;

    /** Every (purpose, channel, language) a clinic can edit: its own override where one exists, else the built-in wording. */
    @Transactional(readOnly = true)
    public List<TemplateRow> list(UUID practiceId) {
        Map<String, MessageTemplate> overrides = new java.util.HashMap<>();
        templates.findByPracticeIdOrderByPurposeAscChannelAscLanguageAsc(practiceId)
                .forEach(t -> overrides.put(key(t.getPurpose(), t.getChannel(), t.getLanguage()), t));
        List<TemplateRow> rows = new ArrayList<>();
        for (MessagePurpose purpose : MessagePurpose.values()) {
            for (MessageChannel channel : CHANNELS) {
                for (String language : LANGUAGES) {
                    MessageTemplate custom = overrides.get(key(purpose, channel, language));
                    if (custom != null) {
                        rows.add(new TemplateRow(purpose, channel, language, custom.getSubject(), custom.getBody(),
                                custom.isActive(), true));
                    } else {
                        DefaultTemplates.find(purpose, language).ifPresent(d ->
                                rows.add(new TemplateRow(purpose, channel, language, d.subject(), d.body(), true, false)));
                    }
                }
            }
        }
        return rows;
    }

    /** The text to send: the clinic's active override, else the built-in wording; empty when neither exists. */
    @Transactional(readOnly = true)
    public Optional<DefaultTemplates.Text> resolve(UUID practiceId, MessageChannel channel, MessagePurpose purpose, String language) {
        Optional<MessageTemplate> custom = templates.findByPracticeIdAndChannelAndPurposeAndLanguage(practiceId, channel, purpose, language);
        if (custom.isPresent() && custom.get().isActive()) {
            return Optional.of(new DefaultTemplates.Text(custom.get().getSubject(), custom.get().getBody()));
        }
        if (custom.isPresent()) {
            return Optional.empty();
        }
        return DefaultTemplates.find(purpose, language);
    }

    @Transactional
    public TemplateRow upsert(UUID practiceId, TemplateUpsert request) {
        MessageTemplate template = templates.findByPracticeIdAndChannelAndPurposeAndLanguage(
                        practiceId, request.channel(), request.purpose(), request.language())
                .orElseGet(() -> MessageTemplate.builder().practiceId(practiceId).channel(request.channel())
                        .purpose(request.purpose()).language(request.language()).build());
        template.setSubject(request.subject());
        template.setBody(request.body());
        template.setActive(request.active() == null || request.active());
        templates.save(template);
        return new TemplateRow(template.getPurpose(), template.getChannel(), template.getLanguage(),
                template.getSubject(), template.getBody(), template.isActive(), true);
    }

    @Transactional
    public void revert(UUID practiceId, MessagePurpose purpose, MessageChannel channel, String language) {
        templates.findByPracticeIdAndChannelAndPurposeAndLanguage(practiceId, channel, purpose, language)
                .ifPresent(templates::delete);
    }

    public Preview preview(PreviewRequest request) {
        return new Preview(TemplateRenderer.render(request.subject(), SAMPLE), TemplateRenderer.render(request.body(), SAMPLE));
    }

    private static String key(MessagePurpose purpose, MessageChannel channel, String language) {
        return purpose + "|" + channel + "|" + language;
    }
}
