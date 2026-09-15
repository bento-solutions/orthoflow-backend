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
            log.info("Voice NLU fallback enabled: provider={} model={}",
                    provider.name(), properties.getNlu().getModel());
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
                    .clarification("That was too long for me to interpret in one go. "
                            + "Could you break it into shorter findings?")
                    .build();
        }

        if (!provider.isAvailable()) {
            return InterpretResponse.builder()
                    .resolver("llm")
                    .provider(provider.name())
                    .confidence(0)
                    .entities(Map.of())
                    .clarification("I didn't catch that. Try naming the tooth and the finding, "
                            + "for example \"upper right first molar, recurrent caries\".")
                    .build();
        }

        NluInterpretation interpretation = provider.interpret(request);

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
