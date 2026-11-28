package com.orthoflow.messaging.application.service;

import com.orthoflow.messaging.domain.model.MessagePurpose;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Default plain-text wording loaded from {@code whatsapp-templates.yml}.
 * Editing {@code whatsapp-templates.yml} updates wording in one place across French,
 * Arabic, and English; per-clinic database overrides still take precedence.
 */
public final class DefaultTemplates {

    public record Text(String subject, String body) {
    }

    private static final Map<MessagePurpose, Map<String, Text>> TEMPLATES = loadFromYaml();

    private DefaultTemplates() {
    }

    public static Optional<Text> find(MessagePurpose purpose, String language) {
        String lang = language == null ? "fr" : language;
        Map<String, Text> byLang = TEMPLATES.get(purpose);
        if (byLang == null) {
            return Optional.empty();
        }
        Text text = byLang.getOrDefault(lang, byLang.get("fr"));
        return Optional.ofNullable(text);
    }

    /** Formats the registration invite snippet appended to confirmations and reminders when the form is incomplete. */
    public static String registrationSnippet(String language, String link) {
        if (link == null || link.isBlank()) {
            return "";
        }
        String lang = language == null ? "fr" : language;
        return switch (lang) {
            case "ar" -> "\nاملؤوا هذه الاستمارة القصيرة (5 دقائق) لتسهيل استشارتكم: " + link;
            case "en" -> "\nFill this 5-minute form to make your consultation simple: " + link;
            default -> "\nRemplissez ce formulaire en 5 minutes pour simplifier votre consultation : " + link;
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<MessagePurpose, Map<String, Text>> loadFromYaml() {
        Map<MessagePurpose, Map<String, Text>> result = new EnumMap<>(MessagePurpose.class);
        try (InputStream in = DefaultTemplates.class.getResourceAsStream("/whatsapp-templates.yml")) {
            if (in == null) {
                return result;
            }
            Object loaded = new Yaml().load(in);
            if (!(loaded instanceof Map<?, ?> root)) {
                return result;
            }
            for (Map.Entry<?, ?> entry : root.entrySet()) {
                MessagePurpose purpose;
                try {
                    purpose = MessagePurpose.valueOf(String.valueOf(entry.getKey()));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                if (!(entry.getValue() instanceof Map<?, ?> langs)) {
                    continue;
                }
                Map<String, Text> byLang = new HashMap<>();
                for (Map.Entry<?, ?> langEntry : langs.entrySet()) {
                    if (langEntry.getValue() instanceof Map<?, ?> fields) {
                        Object subj = fields.get("subject");
                        Object body = fields.get("body");
                        if (body != null) {
                            byLang.put(String.valueOf(langEntry.getKey()),
                                    new Text(subj == null ? "" : String.valueOf(subj), String.valueOf(body)));
                        }
                    }
                }
                result.put(purpose, Map.copyOf(byLang));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load whatsapp-templates.yml", e);
        }
        return result;
    }
}
