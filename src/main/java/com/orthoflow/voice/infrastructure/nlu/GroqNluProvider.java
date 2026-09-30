package com.orthoflow.voice.infrastructure.nlu;

import com.orthoflow.voice.infrastructure.provider.ChatCompletionClient;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;
import org.springframework.stereotype.Component;

/**
 * Interpretation fallback on Groq. A mid-sized open model on Groq's hardware
 * answers a short classification in well under a second, which is the point
 * of a fallback that runs while the dentist is waiting.
 */
@Component
public class GroqNluProvider extends HostedChatNluProvider {

    private final VoiceNluProperties properties;

    public GroqNluProvider(VoiceNluProperties properties, VoiceProviderProperties vendors,
                           NluPromptBuilder promptBuilder, NluResponseParser responseParser,
                           ChatCompletionClient chat) {
        super(properties, vendors, promptBuilder, responseParser, chat);
        this.properties = properties;
    }

    @Override
    public String name() {
        return "groq";
    }

    @Override
    String model() {
        return properties.getNlu().getGroqModel();
    }
}
