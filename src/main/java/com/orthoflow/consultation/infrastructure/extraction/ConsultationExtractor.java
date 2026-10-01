package com.orthoflow.consultation.infrastructure.extraction;

import com.orthoflow.consultation.domain.model.ConsultationDraft;
import com.orthoflow.consultation.infrastructure.extraction.ConsultationPromptBuilder.CatalogEntry;
import com.orthoflow.voice.infrastructure.provider.ChatCompletionClient;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a consultation's transcript and says what it established.
 *
 * <p>Tries each model route in the configured chain until one answers with
 * something {@link ConsultationDraftParser} accepts; a route that is down,
 * rate-limited, or answers with something that is not the JSON asked for counts
 * as failed and the next gets the same transcript. If none answers — or the
 * model is switched off — the rule-based reading is returned instead, with a
 * tag saying why, so the side panel is never left blank. The two are never
 * combined: a model that answered is trusted on its own.
 *
 * <p>This is stateless: it re-reads the whole transcript every time. A
 * conversation is a few thousand words, a model reads that in a couple of
 * seconds, and re-reading is what lets a later correction ("non, plutôt le 06…")
 * replace an earlier mistake without any bookkeeping here.
 */
@Component
@Slf4j
public class ConsultationExtractor {

    /** One model on one vendor. */
    public record Route(String vendor, String model) {
        static Route parse(String spec) {
            String trimmed = spec == null ? "" : spec.trim();
            int colon = trimmed.indexOf(':');
            if (colon <= 0 || colon == trimmed.length() - 1) return null;
            return new Route(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim());
        }
    }

    /**
     * @param error null when a model read the conversation; otherwise
     *              {@code extraction-disabled} or {@code extraction-failed},
     *              and {@code draft} is the rule-based reading
     * @param truncated the transcript was longer than the model is sent
     */
    public record Extraction(ConsultationDraft draft, String error, boolean truncated) {}

    /** No model is configured: the rules' reading is all there will be. */
    public static final String DISABLED = "extraction-disabled";

    /** Every model route failed this time: the rules' reading stands in for it. */
    public static final String FAILED = "extraction-failed";

    private final ConsultationExtractionProperties properties;
    private final VoiceProviderProperties vendors;
    private final ChatCompletionClient chat;

    public ConsultationExtractor(ConsultationExtractionProperties properties, VoiceProviderProperties vendors,
                                 ChatCompletionClient chat) {
        this.properties = properties;
        this.vendors = vendors;
        this.chat = chat;
    }

    /** Routes that have a key and an endpoint, in the order they are tried. */
    public List<Route> routes() {
        ConsultationExtractionProperties.Extraction cfg = properties.getExtraction();
        List<Route> routes = new ArrayList<>();
        routes.add(new Route(cfg.getProvider(), cfg.getModel()));
        if (cfg.getFallbacks() != null) {
            for (String spec : cfg.getFallbacks()) {
                Route route = Route.parse(spec);
                if (route != null && !routes.contains(route)) routes.add(route);
            }
        }
        return routes.stream().filter(route -> !key(route).isBlank() && !baseUrl(route).isBlank()).toList();
    }

    public boolean isModelEnabled() {
        return properties.getExtraction().isEnabled() && !routes().isEmpty();
    }

    public Extraction extract(String transcript, List<CatalogEntry> catalog, LocalDate today) {
        ConsultationDraft rules = RuleBasedExtractor.extract(transcript);
        if (!properties.getExtraction().isEnabled()) {
            return new Extraction(rules, DISABLED, false);
        }
        List<Route> routes = routes();
        if (routes.isEmpty()) {
            return new Extraction(rules, DISABLED, false);
        }
        if (transcript == null || transcript.isBlank()) {
            return new Extraction(rules, null, false);
        }

        ConsultationExtractionProperties.Extraction cfg = properties.getExtraction();
        boolean truncated = transcript.length() > cfg.getMaxTranscriptChars();
        String sent = truncated ? transcript.substring(transcript.length() - cfg.getMaxTranscriptChars()) : transcript;
        List<CatalogEntry> offered = catalog == null ? List.of()
                : catalog.stream().limit(cfg.getMaxCatalogEntries()).toList();

        String system = ConsultationPromptBuilder.system(cfg.getLanguage(), offered);
        String user = ConsultationPromptBuilder.user(today, sent, truncated);

        for (Route route : routes) {
            ChatCompletionClient.Result result = chat.complete(new ChatCompletionClient.Request(
                    baseUrl(route), key(route), route.model(), system, user,
                    cfg.getMaxOutputTokens(), true, cfg.getTimeoutMs()));
            if (!result.succeeded()) {
                log.warn("Consultation extraction on {}:{} failed ({}) — trying the next route",
                        route.vendor(), route.model(), result.error());
                continue;
            }
            // Quotes are checked against what was sent, not what was kept: a
            // model cannot cite the part that was cut.
            ConsultationDraftParser.Result parsed = ConsultationDraftParser.parse(
                    result.text(), sent, offered, today, route.vendor() + ":" + route.model());
            if (parsed == null) {
                log.warn("Consultation extraction on {}:{} did not answer with a JSON object — trying the next route",
                        route.vendor(), route.model());
                continue;
            }
            // The model's answer stands alone. Adding what the rules found to it
            // put a spouse's allergy and an injected phrase on a patient the
            // model had read correctly (see RuleBasedExtractor).
            return new Extraction(parsed.draft(), null, truncated);
        }
        log.warn("Consultation extraction: every route failed; using the rule-based reading");
        return new Extraction(rules, FAILED, truncated);
    }

    private String key(Route route) {
        return vendors.keyFor(route.vendor(), properties.getExtraction().getProvider(), "");
    }

    private String baseUrl(Route route) {
        VoiceProviderProperties.Vendor vendor = vendors.vendor(route.vendor());
        return vendor == null ? "" : vendor.base();
    }
}
