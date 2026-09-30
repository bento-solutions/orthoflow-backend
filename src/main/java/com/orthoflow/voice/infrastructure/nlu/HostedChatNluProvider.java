package com.orthoflow.voice.infrastructure.nlu;

import com.orthoflow.voice.application.dto.InterpretRequest;
import com.orthoflow.voice.infrastructure.provider.ChatCompletionClient;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;

/**
 * Interpretation on a hosted OpenAI-compatible model, used as a fallback when
 * the primary NLU provider is overloaded or unreachable.
 *
 * <p>Same prompt, same schema and same response parser as every other
 * provider, so a fallback cannot interpret an utterance more loosely than the
 * primary would: a model's reply is still untrusted input that must name an
 * offered intent and catalogued finding codes, or become a question.
 */
abstract class HostedChatNluProvider implements NluProvider {

    private final VoiceNluProperties properties;
    private final VoiceProviderProperties vendors;
    private final NluPromptBuilder promptBuilder;
    private final NluResponseParser responseParser;
    private final ChatCompletionClient chat;

    HostedChatNluProvider(VoiceNluProperties properties, VoiceProviderProperties vendors,
                          NluPromptBuilder promptBuilder, NluResponseParser responseParser,
                          ChatCompletionClient chat) {
        this.properties = properties;
        this.vendors = vendors;
        this.promptBuilder = promptBuilder;
        this.responseParser = responseParser;
        this.chat = chat;
    }

    /** The model id to request from this vendor. */
    abstract String model();

    private VoiceProviderProperties.Vendor vendor() {
        return vendors.vendor(name());
    }

    @Override
    public boolean isAvailable() {
        VoiceProviderProperties.Vendor vendor = vendor();
        return vendor != null && vendor.hasKey() && !vendor.base().isBlank()
                && model() != null && !model().isBlank();
    }

    @Override
    public NluInterpretation interpret(InterpretRequest request) {
        if (!isAvailable()) {
            return NluInterpretation.unavailable(name(), name() + " NLU has no API key or model configured");
        }
        ChatCompletionClient.Result result = chat.complete(new ChatCompletionClient.Request(
                vendor().base(),
                vendor().getApiKey().trim(),
                model(),
                // The JSON contract is restated in the system prompt, which is
                // what json_object mode needs to be allowed at all.
                promptBuilder.systemPrompt(request),
                promptBuilder.userPrompt(request),
                properties.getNlu().getMaxOutputTokens(),
                true,
                properties.getNlu().getTimeoutMs()));
        if (!result.succeeded()) {
            return NluInterpretation.unavailable(name(), "NLU " + result.error());
        }
        return responseParser.parse(result.text(), request, name());
    }
}
