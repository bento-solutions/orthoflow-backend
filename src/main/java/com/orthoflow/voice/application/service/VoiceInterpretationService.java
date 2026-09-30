package com.orthoflow.voice.application.service;

import com.orthoflow.voice.application.dto.InterpretRequest;
import com.orthoflow.voice.application.dto.InterpretResponse;
import com.orthoflow.voice.infrastructure.nlu.NluInterpretation;
import com.orthoflow.voice.infrastructure.nlu.NluProvider;
import com.orthoflow.voice.infrastructure.nlu.VoiceNluProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * The second half of a two-stage pipeline. The first half — a deterministic
 * grammar — runs in the browser and never calls this: structured clinical
 * dictation ("upper right first molar, recurrent caries") resolves on-device,
 * instantly, at no cost, with nothing leaving the machine.
 *
 * <p>Only utterances the grammar could not parse arrive here, and only if a
 * provider has been configured. When none has, every such utterance becomes a
 * question to the doctor rather than a guess — which is a usable product, just
 * a more literal-minded one.
 */
@Service
@Slf4j
public class VoiceInterpretationService {

    private final VoiceNluProperties properties;
    private final List<NluProvider> providers;

    public VoiceInterpretationService(VoiceNluProperties properties, List<NluProvider> providers) {
        this.properties = properties;
        this.providers = providers;
    }

    @PostConstruct
    void reportConfiguration() {
        String configured = properties.getNlu().getProvider();
        NluProvider provider = selectProvider();
        if (provider.isAvailable()) {
            log.info("Voice NLU enabled: provider={} fallbacks={}",
                    provider.name(), fallbacks(provider).stream().map(NluProvider::name).toList());
        } else if (!"disabled".equalsIgnoreCase(configured)) {
            log.warn("Voice NLU provider '{}' is selected but not usable — check "
                            + "orthoflow.voice.nlu.api-key / base-url. Falling back to grammar-only.",
                    configured);
        } else {
            log.info("Voice NLU fallback disabled (grammar-only). Utterances the on-device grammar "
                    + "cannot parse will be answered with a clarifying question. Set "
                    + "orthoflow.voice.nlu.provider to 'gemini', 'anthropic' or 'openai-compatible' to enable it.");
        }
    }

    public InterpretResponse interpret(InterpretRequest request) {
        NluProvider provider = selectProvider();

        String transcript = request.getTranscript() == null ? "" : request.getTranscript().trim();
        int limit = properties.getNlu().getMaxTranscriptChars();
        if (transcript.length() > limit) {
            // Truncating would cut a clinical sentence mid-clause and change what
            // it means, so refuse the whole thing and say so.
            return InterpretResponse.builder()
                    .resolver("llm")
                    .provider(provider.name())
                    .confidence(0)
                    .entities(Map.of())
                    .clarification(french(request)
                            ? "C'était trop long pour moi. Dictez les constatations une par une."
                            : "That was too long for me to interpret in one go. "
                              + "Could you break it into shorter findings?")
                    .build();
        }

        if (!provider.isAvailable()) {
            // A primary with no key but a working fallback is still a working
            // NLU; only a disabled or fully unconfigured one becomes a question.
            List<NluProvider> alternatives = fallbacks(provider);
            if (alternatives.isEmpty()) {
                return InterpretResponse.builder()
                        .resolver("llm")
                        .provider(provider.name())
                        .confidence(0)
                        .entities(Map.of())
                        .clarification(french(request)
                                ? "Je n'ai pas compris. Donnez la dent et la constatation, "
                                  + "par exemple « dent 16, carie récurrente »."
                                : "I didn't catch that. Try naming the tooth and the finding, "
                                  + "for example \"upper right first molar, recurrent caries\".")
                        .build();
            }
            provider = alternatives.get(0);
        }

        NluInterpretation interpretation = provider.interpret(request);

        // Unavailable is not the same as "didn't understand": an overloaded
        // primary says nothing about the utterance, so the next provider gets
        // the same question. A clarification is an answer and is kept.
        if (interpretation.error() != null && !interpretation.hasIntent()) {
            for (NluProvider fallback : fallbacks(provider)) {
                NluInterpretation retried = fallback.interpret(request);
                if (retried.error() == null || retried.hasIntent()) {
                    log.info("Voice NLU answered by fallback '{}' after {} failed: {}",
                            fallback.name(), provider.name(), interpretation.error());
                    interpretation = retried;
                    break;
                }
            }
        }

        return InterpretResponse.builder()
                .intent(interpretation.intent())
                .entities(interpretation.entities() == null ? Map.of() : interpretation.entities())
                .confidence(interpretation.confidence())
                .clarification(interpretation.clarification())
                .resolver("llm")
                .provider(interpretation.providerName())
                .error(interpretation.error())
                .build();
    }

    private static boolean french(InterpretRequest request) {
        return request.getLocale() != null && request.getLocale().toLowerCase().startsWith("fr");
    }

    /**
     * The configured fallbacks that are usable, excluding the primary. Empty
     * while the primary is {@code disabled}: a fallback is not a way to send
     * transcripts to a vendor nobody chose.
     */
    private List<NluProvider> fallbacks(NluProvider primary) {
        if ("disabled".equals(primary.name())) {
            return List.of();
        }
        List<String> names = properties.getNlu().getFallbacks();
        if (names == null) {
            return List.of();
        }
        return names.stream()
                .map(String::trim)
                .filter(name -> !name.equalsIgnoreCase(primary.name()))
                .flatMap(name -> providers.stream().filter(p -> p.name().equalsIgnoreCase(name)).limit(1))
                .filter(NluProvider::isAvailable)
                .toList();
    }

    private NluProvider selectProvider() {
        String configured = properties.getNlu().getProvider();
        return providers.stream()
                .filter(p -> p.name().equalsIgnoreCase(configured))
                .findFirst()
                .orElseGet(() -> providers.stream()
                        .filter(p -> p.name().equals("disabled"))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No disabled NLU provider registered")));
    }
}
