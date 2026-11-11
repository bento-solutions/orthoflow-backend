package com.orthoflow.consultation.infrastructure.extraction;

import com.orthoflow.voice.infrastructure.provider.ChatCompletionClient;
import com.orthoflow.voice.infrastructure.provider.VoiceProviderProperties;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Chooses the tail reading's model by measurement (docs/VOICE-COCKPIT-PLAN.md
 * WP6.6). Off unless asked for, because it calls paid models:
 *
 * <pre>
 *   VOICE_GROQ_API_KEY=… mvn -o test -Dtest=TailReadingBenchmarkTest -Dreader.benchmark=true \
 *     -Dreader.routes=groq:openai/gpt-oss-20b,groq:qwen/qwen3.8-27b
 * </pre>
 *
 * It feeds the 10 Nov 2026 session's intake lines one by one, as the browser
 * would, and prints each route's p50 and p95 latency and how many of the
 * expected items it found. Take the fastest route that finds them all, and
 * write the numbers in docs/CONSULTATION.md §5.
 */
@EnabledIfSystemProperty(named = "reader.benchmark", matches = "true")
class TailReadingBenchmarkTest {

    private static final List<String> LINES = List.of(
            "Vous avez une allergie à l'ananas?",
            "Pour les allergies, vous avez une allergie à l'ananas, vous avez une allergie à la pénicilline, et je vois aussi que vous avez une réaction au kiwi.",
            "Vous avez dit que vous avez des traitements en cours. OK, donc vous êtes, vous prenez, vous prenez du",
            "Du paracétamol.",
            "Et pour les antécédents médicaux, est-ce que vous avez— OK, je vois. Donc vous avez, vous êtes diabétique de type 2.",
            "Pas diabète de type 2, diabète de type 1. OK, OK, diabète de type 1.",
            "Donc vous avez dans 16 caries occlusales.");
    private static final List<String> EXPECTED = List.of("ananas", "penicilline", "kiwi", "paracetamol", "diabete de type 1");

    @Test
    void measuresEachRoute() {
        String routes = System.getProperty("reader.routes", "groq:openai/gpt-oss-20b");
        VoiceProviderProperties vendors = new VoiceProviderProperties();
        vendors.setRequireDataProtection(false);
        vendors.getGroq().setApiKey(System.getenv().getOrDefault("VOICE_GROQ_API_KEY", ""));
        vendors.getGemini().setApiKey(System.getenv().getOrDefault("VOICE_GEMINI_API_KEY", ""));
        vendors.getDeepseek().setApiKey(System.getenv().getOrDefault("VOICE_DEEPSEEK_API_KEY", ""));
        for (String spec : routes.split(",")) {
            ConsultationExtractor.Route route = ConsultationExtractor.Route.parse(spec);
            if (route == null) continue;
            ConsultationExtractionProperties properties = new ConsultationExtractionProperties();
            properties.setEnabled(true);
            properties.getExtraction().setEnabled(true);
            properties.getExtraction().setProvider(route.vendor());
            properties.getExtraction().setModel(route.model());
            properties.getExtraction().setFallbacks(List.of());
            properties.getExtraction().getTail().setEnabled(true);
            ConsultationExtractor extractor = new ConsultationExtractor(properties, vendors, new ChatCompletionClient(new com.fasterxml.jackson.databind.ObjectMapper()));
            List<Long> latencies = new ArrayList<>();
            List<String> found = new ArrayList<>();
            StringBuilder transcript = new StringBuilder();
            for (String line : LINES) {
                transcript.append(line).append('\n');
                long started = System.nanoTime();
                ConsultationExtractor.Extraction result = extractor.extractTail(transcript.toString(), found, List.of(), LocalDate.now());
                latencies.add((System.nanoTime() - started) / 1_000_000);
                result.draft().allergies().forEach(a -> found.add(a.substance()));
                result.draft().activeTreatments().forEach(t -> found.add(t.label()));
                result.draft().medicalHistory().forEach(h -> found.add(h.label()));
                result.corrections().forEach(c -> { if (c.nowLabel() != null) found.add(c.nowLabel()); });
            }
            latencies.sort(Long::compare);
            long hits = EXPECTED.stream().filter(e -> found.stream().anyMatch(f -> TranscriptText.normalize(f).contains(e))).count();
            System.out.printf("%s  p50=%d ms  p95=%d ms  found %d/%d %s%n", spec,
                    latencies.get(latencies.size() / 2), latencies.get((int) Math.ceil(latencies.size() * 0.95) - 1),
                    hits, EXPECTED.size(), found);
        }
    }
}
