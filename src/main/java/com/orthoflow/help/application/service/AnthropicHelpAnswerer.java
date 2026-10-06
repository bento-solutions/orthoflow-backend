package com.orthoflow.help.application.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.stream.Collectors;

/** The Claude-backed answerer. Unavailable (and never called) unless the feature is enabled and a key is set. */
@Component
public class AnthropicHelpAnswerer implements HelpAnswerer {

    private final HelpProperties properties;
    private final AnthropicClient client;

    public AnthropicHelpAnswerer(HelpProperties properties) {
        this.properties = properties;
        this.client = properties.isEnabled() && properties.getApiKey() != null && !properties.getApiKey().isBlank()
                ? AnthropicOkHttpClient.builder().apiKey(properties.getApiKey()).timeout(Duration.ofMillis(properties.getTimeoutMs())).build()
                : null;
    }

    @Override
    public boolean available() {
        return client != null;
    }

    @Override
    public String answer(String system, String question) {
        Message response = client.messages().create(MessageCreateParams.builder()
                .model(properties.getModel())
                .maxTokens(properties.getMaxOutputTokens())
                .system(system)
                .addUserMessage(question)
                .build());
        return response.content().stream().map(ContentBlock::text).filter(java.util.Optional::isPresent)
                .map(block -> block.get().text()).collect(Collectors.joining()).trim();
    }
}
