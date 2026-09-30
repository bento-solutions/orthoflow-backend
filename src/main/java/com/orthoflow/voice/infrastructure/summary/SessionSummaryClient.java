package com.orthoflow.voice.infrastructure.summary;

import com.orthoflow.voice.infrastructure.provider.ChatCompletionClient;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Generates the consultation narrative from what the session actually
 * recorded, on the first model in the configured chain that produces a
 * summary the caller accepts.
 *
 * <p>The chain is {@code provider}/{@code model} followed by each of
 * {@code fallbacks} ({@code vendor:model}). A route is skipped when its vendor
 * has no key; a route whose output the caller rejects — a tooth the records
 * never mention, a recorded tooth left out — counts as a failure, and the next
 * route gets the same records.
 *
 * <p>Returns null when every route failed. A consultation whose summary could
 * not be generated is not a lost consultation: the caller renders the records
 * deterministically instead.
 */
@Component
@Slf4j
public class SessionSummaryClient {

    /** One model on one vendor. */
    public record Route(String vendor, String model) {
        static Route parse(String spec) {
            String trimmed = spec == null ? "" : spec.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) return null;
            return new Route(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
        }
    }

    /** The text that was accepted, and which route produced it. */
    public record Generated(String text, Route route) {}

    private final VoiceSummaryProperties properties;
    private final VoiceProviderProperties vendors;
    private final ChatCompletionClient chat;

    public SessionSummaryClient(VoiceSummaryProperties properties, VoiceProviderProperties vendors,
                                ChatCompletionClient chat) {
        this.properties = properties;
        this.vendors = vendors;
        this.chat = chat;
    }

    /** Routes that have a key, in the order they are tried. */
    public List<Route> routes() {
        List<Route> routes = new ArrayList<>();
        routes.add(new Route(properties.getProvider(), properties.getModel()));
        if (properties.getFallbacks() != null) {
            for (String spec : properties.getFallbacks()) {
                Route route = Route.parse(spec);
                if (route != null && !routes.contains(route)) routes.add(route);
            }
        }
        return routes.stream().filter(route -> !key(route).isBlank() && !baseUrl(route).isBlank()).toList();
    }

    public boolean isConfigured() {
        return !routes().isEmpty();
    }

    /**
     * @param accept returns null to accept the text, or why it was rejected
     * @return the first accepted summary, or null
     */
    public Generated summarise(String systemPrompt, String userPrompt, Function<String, String> accept) {
        for (Route route : routes()) {
            ChatCompletionClient.Result result = chat.complete(new ChatCompletionClient.Request(
                    baseUrl(route), key(route), route.model(), systemPrompt, userPrompt,
                    properties.getMaxOutputTokens(), false, properties.getTimeoutMs()));
            if (!result.succeeded()) {
                log.warn("Session summary on {}:{} failed ({}) — trying the next route",
                        route.vendor(), route.model(), result.error());
                continue;
            }
            String rejection = accept.apply(result.text());
            if (rejection != null) {
                log.warn("Session summary on {}:{} rejected: {} — trying the next route",
                        route.vendor(), route.model(), rejection);
                continue;
            }
            return new Generated(result.text(), route);
        }
        return null;
    }

    private String key(Route route) {
        return vendors.keyFor(route.vendor(), properties.getProvider(), properties.getApiKey());
    }

    /**
     * {@code orthoflow.voice.summary.base-url} applies to the primary vendor,
     * so a deployment that pointed it elsewhere keeps working; fallbacks use
     * their vendor's own endpoint.
     */
    private String baseUrl(Route route) {
        if (route.vendor().equalsIgnoreCase(properties.getProvider())
                && properties.getBaseUrl() != null && !properties.getBaseUrl().isBlank()) {
            return properties.getBaseUrl().replaceAll("/+$", "");
        }
        VoiceProviderProperties.Vendor vendor = vendors.vendor(route.vendor());
        return vendor == null ? "" : vendor.base();
    }
}
