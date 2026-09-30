package com.orthoflow.voice.infrastructure.nlu;

import com.orthoflow.voice.infrastructure.provider.ChatCompletionClient;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;
import org.springframework.stereotype.Component;

/** Interpretation fallback on DeepSeek's OpenAI-compatible API. */
@Component
public class DeepSeekNluProvider extends HostedChatNluProvider {

    private final VoiceNluProperties properties;

    public DeepSeekNluProvider(VoiceNluProperties properties, VoiceProviderProperties vendors,
                               NluPromptBuilder promptBuilder, NluResponseParser responseParser,
                               ChatCompletionClient chat) {
        super(properties, vendors, promptBuilder, responseParser, chat);
        this.properties = properties;
    }

    @Override
    public String name() {
        return "deepseek";
    }

    @Override
    String model() {
        return properties.getNlu().getDeepseekModel();
    }
}
