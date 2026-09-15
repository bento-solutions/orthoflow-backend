package com.orthoflow.voice.infrastructure.nlu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.voice.application.dto.InterpretRequest;
import com.orthoflow.voice.application.dto.IntentDescriptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns a model's text reply into a checked {@link NluInterpretation}.
 *
 * <p>This is a trust boundary, not a convenience. Whatever a model returns is
 * untrusted input: it may claim an intent that was never offered, invent a
 * finding code, or return prose instead of JSON. Anything that does not
 * survive the checks here becomes a clarification — the system asks a question
 * rather than acting on a response it could not verify.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class NluResponseParser {

    private final ObjectMapper objectMapper;

    public NluInterpretation parse(String rawText, InterpretRequest request, String providerName) {
        if (rawText == null || rawText.isBlank()) {
            return clarify(providerName, rawText, "I didn't get a usable answer. Could you repeat that?");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(extractJsonObject(rawText));
        } catch (Exception e) {
            log.warn("Voice NLU returned unparseable output from provider {}", providerName);
            return clarify(providerName, rawText, "I couldn't interpret that. Could you rephrase it?");
        }

        String intent = text(root, "intent");
        String clarification = text(root, "clarification");
        double confidence = root.path("confidence").asDouble(0);

        if (intent == null || intent.isBlank()) {
            return NluInterpretation.builder()
                    .providerName(providerName)
                    .rawResponse(rawText)
                    .confidence(confidence)
                    .clarification(clarification != null ? clarification
                            : "I'm not sure what to do with that. Could you say it another way?")
                    .build();
        }

        // A command that was never on the menu is not a command. Context
        // scoping (audit XII.4 §7) is only real if it is enforced here rather
        // than merely described in the prompt.
        Set<String> offered = request.getAvailableIntents().stream()
                .map(IntentDescriptor::id)
                .collect(Collectors.toSet());
        if (!offered.contains(intent)) {
            log.warn("Voice NLU proposed intent '{}' that was not offered; refusing", intent);
            return clarify(providerName, rawText,
                    "I can't do that from this screen. Could you say what you'd like to record?");
        }

        Map<String, Object> entities = readEntities(root);

        // Finding codes are the other thing a model can invent. Drop unknown
        // ones rather than letting them fail deeper in, where the doctor would
        // see an opaque error mid-examination instead of a question.
        if (request.getFindingCodes() != null && !request.getFindingCodes().isEmpty()) {
            entities = stripUnknownFindingCodes(entities, Set.copyOf(request.getFindingCodes()));
        }

        return NluInterpretation.builder()
                .intent(intent)
                .entities(entities)
                .confidence(confidence)
                .clarification(clarification)
                .rawResponse(rawText)
                .providerName(providerName)
                .build();
    }

    /**
     * Models occasionally wrap JSON in a markdown fence or a sentence despite
     * being told not to. A fenced block is stripped first, then the outermost
     * brace-balanced span is taken — which cannot smuggle anything past the
     * validation above.
     */
    private String extractJsonObject(String raw) {
        String trimmed = raw.trim();

        // Strip markdown code fences: ```json ... ``` or ``` ... ```
        java.util.regex.Matcher fenced = java.util.regex.Pattern
                .compile("```(?:json)?\\s*\\n?(.*?)\\n?\\s*```", java.util.regex.Pattern.DOTALL)
                .matcher(trimmed);
        if (fenced.find()) {
            trimmed = fenced.group(1).trim();
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return trimmed;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readEntities(JsonNode root) {
        JsonNode node = root.path("entities");
        if (!node.isObject()) return new LinkedHashMap<>();
        try {
            Map<String, Object> entities = objectMapper.convertValue(node, Map.class);
            return normaliseIdentifiers(entities);
        } catch (IllegalArgumentException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Identifiers that are strings everywhere else in the system stay strings
     * here, whatever JSON type the model chose for them.
     *
     * <p>Models return {@code "fdi": 16} as often as {@code "fdi": "16"}, and
     * Jackson faithfully preserves the difference. Canonicalising here fixes
     * every consumer at once — including nested identifiers inside the
     * {@code findings} array.
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normaliseIdentifiers(Map<String, Object> entities) {
        Object fdi = entities.get("fdi");
        if (fdi instanceof Number number) {
            entities.put("fdi", String.valueOf(number.longValue()));
        }
        // Normalise fdi and findingId inside each finding in the findings array.
        Object findings = entities.get("findings");
        if (findings instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> finding = (Map<String, Object>) map;
                    Object nestedFdi = finding.get("fdi");
                    if (nestedFdi instanceof Number n) {
                        finding.put("fdi", String.valueOf(n.longValue()));
                    }
                    Object findingId = finding.get("findingId");
                    if (findingId instanceof Number n) {
                        finding.put("findingId", String.valueOf(n.longValue()));
                    }
                }
            }
        }
        return entities;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> stripUnknownFindingCodes(Map<String, Object> entities, Set<String> known) {
        Map<String, Object> cleaned = new LinkedHashMap<>(entities);
        Object findings = cleaned.get("findings");
        if (findings instanceof Iterable<?> list) {
            var kept = new java.util.ArrayList<Object>();
            for (Object item : list) {
                String code = item instanceof Map<?, ?> map
                        ? String.valueOf(((Map<String, Object>) map).get("code"))
                        : String.valueOf(item);
                if (known.contains(code)) {
                    kept.add(item);
                } else {
                    log.warn("Voice NLU proposed unknown finding code '{}'; dropped", code);
                }
            }
            cleaned.put("findings", kept);
        }
        Object single = cleaned.get("findingCode");
        if (single != null && !known.contains(String.valueOf(single))) {
            log.warn("Voice NLU proposed unknown finding code '{}'; dropped", single);
            cleaned.remove("findingCode");
        }
        return cleaned;
    }

    private NluInterpretation clarify(String providerName, String rawText, String message) {
        return NluInterpretation.builder()
                .providerName(providerName)
                .rawResponse(rawText)
                .confidence(0)
                .clarification(message)
                .build();
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    /** Convenience for providers that need a mutable map of the request context. */
    public Map<String, Object> emptyEntities() {
        return new HashMap<>();
    }
}
