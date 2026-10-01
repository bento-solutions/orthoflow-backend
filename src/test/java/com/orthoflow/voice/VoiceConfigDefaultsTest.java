package com.orthoflow.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.io.ClassPathResource;

/**
 * The defaults the voice pipeline starts with are written in four places: the
 * application config, the production compose file, the example env file and
 * the comments that explain them. They drifted once — compose defaulted to
 * Whisper while every comment and the measurements said AssemblyAI — which
 * silently changed which recogniser a deployment used. This pins them
 * together, and pins the two that decide whether audio and transcripts leave
 * the machine: they must be off unless someone turns them on.
 */
class VoiceConfigDefaultsTest {

    private static final Path COMPOSE = Path.of("docker-compose.yml");
    private static final Path ENV_EXAMPLE = Path.of(".env.example");

    private static PropertySourcesPropertyResolver applicationYml() throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        for (PropertySource<?> source
                : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        // No environment variables: what is asked is the default, not whatever
        // this machine happens to export.
        sources.addFirst(new MapPropertySource("none", new LinkedHashMap<>()));
        return new PropertySourcesPropertyResolver(sources);
    }

    private static String composeDefault(String variable) throws IOException {
        Matcher m = Pattern
                .compile("^\\s*" + variable + ":\\s*\\$\\{" + variable + ":-([^}]*)}", Pattern.MULTILINE)
                .matcher(Files.readString(COMPOSE));
        assertThat(m.find()).as("docker-compose.yml must pass %s through", variable).isTrue();
        return m.group(1);
    }

    private static String exampleValue(String variable) throws IOException {
        Matcher m = Pattern
                .compile("^" + variable + "=(.*)$", Pattern.MULTILINE)
                .matcher(Files.readString(ENV_EXAMPLE));
        assertThat(m.find()).as(".env.example must list %s", variable).isTrue();
        return m.group(1).trim();
    }

    @Test
    void audioAndTranscriptsStayOnTheMachineUnlessSomeoneTurnsThemOn() throws IOException {
        PropertySourcesPropertyResolver yml = applicationYml();

        assertThat(yml.getProperty("orthoflow.voice.stt.enabled")).isEqualTo("false");
        assertThat(yml.getProperty("orthoflow.voice.nlu.provider")).isEqualTo("disabled");
        assertThat(yml.getProperty("orthoflow.voice.summary.enabled")).isEqualTo("false");
    }

    @Test
    void keepingTheWholeConversationIsOffUnlessSomeoneTurnsItOnAndTheThreePlacesAgree() throws IOException {
        PropertySourcesPropertyResolver yml = applicationYml();

        // A consultation keeps the raw transcript beside the patient: the heaviest promise in the system.
        assertThat(yml.getProperty("orthoflow.consultation.enabled")).isEqualTo("false");
        assertThat(yml.getProperty("orthoflow.consultation.extraction.enabled")).isEqualTo("false");

        assertThat(composeDefault("CONSULTATION_ENABLED")).isEqualTo("false");
        assertThat(exampleValue("CONSULTATION_ENABLED")).isEqualTo("false");
        assertThat(composeDefault("CONSULTATION_EXTRACTION_ENABLED")).isEqualTo("false");
        assertThat(exampleValue("CONSULTATION_EXTRACTION_ENABLED")).isEqualTo("false");

        assertThat(composeDefault("CONSULTATION_EXTRACTION_FALLBACKS"))
                .isEqualTo(yml.getProperty("orthoflow.consultation.extraction.fallbacks"))
                .isEqualTo(exampleValue("CONSULTATION_EXTRACTION_FALLBACKS"));
        assertThat(composeDefault("CONSULTATION_EXTRACTION_MODEL"))
                .isEqualTo(yml.getProperty("orthoflow.consultation.extraction.model"))
                .isEqualTo(exampleValue("CONSULTATION_EXTRACTION_MODEL"));
        assertThat(composeDefault("CONSULTATION_TIMEZONE"))
                .isEqualTo(yml.getProperty("orthoflow.consultation.timezone"))
                .isEqualTo(exampleValue("CONSULTATION_TIMEZONE"));
    }

    @Test
    void theRecogniserChainIsTheSameInConfigComposeAndExample() throws IOException {
        PropertySourcesPropertyResolver yml = applicationYml();

        assertThat(yml.getProperty("orthoflow.voice.stt.provider")).isEqualTo("assemblyai");
        assertThat(composeDefault("VOICE_STT_PROVIDER")).isEqualTo("assemblyai");
        assertThat(exampleValue("VOICE_STT_PROVIDER")).isEqualTo("assemblyai");

        String fallbacks = yml.getProperty("orthoflow.voice.stt.fallbacks");
        assertThat(composeDefault("VOICE_STT_FALLBACKS")).isEqualTo(fallbacks);
        assertThat(exampleValue("VOICE_STT_FALLBACKS")).isEqualTo(fallbacks);
    }

    @Test
    void composeDoesNotSwitchOnWhatTheConfigLeavesOff() throws IOException {
        assertThat(composeDefault("VOICE_STT_ENABLED")).isEqualTo("false");
        assertThat(composeDefault("VOICE_NLU_PROVIDER")).isEqualTo("disabled");
        assertThat(composeDefault("VOICE_SUMMARY_ENABLED")).isEqualTo("false");
        assertThat(exampleValue("VOICE_STT_ENABLED")).isEqualTo("false");
        assertThat(exampleValue("VOICE_NLU_PROVIDER")).isEqualTo("disabled");
        assertThat(exampleValue("VOICE_SUMMARY_ENABLED")).isEqualTo("false");
    }

    @Test
    void anUnprofiledRunLogsSqlButNoOtherDeploymentDoes() throws IOException {
        PropertySourcesPropertyResolver base = applicationYml();
        assertThat(base.getProperty("logging.level.org.hibernate.SQL")).isEqualTo("WARN");
        assertThat(base.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(base.getProperty("spring.profiles.default")).isEqualTo("dev");

        MutablePropertySources dev = new MutablePropertySources();
        for (PropertySource<?> source
                : new YamlPropertySourceLoader().load("dev", new ClassPathResource("application-dev.yml"))) {
            dev.addLast(source);
        }
        assertThat(new PropertySourcesPropertyResolver(dev).getProperty("logging.level.org.hibernate.SQL"))
                .isEqualTo("DEBUG");
    }

    @Test
    void theRateLimitsInTheConfigAreTheOnesTheCodeFallsBackTo() throws IOException {
        PropertySourcesPropertyResolver yml = applicationYml();
        com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimitProperties code =
                new com.orthoflow.voice.infrastructure.ratelimit.VoiceRateLimitProperties();

        assertThat(yml.getProperty("orthoflow.voice.rate-limit.enabled")).isEqualTo("true");
        for (String route : new String[] {"transcribe", "interpret", "summarize"}) {
            var limit = switch (route) {
                case "transcribe" -> code.getTranscribe();
                case "interpret" -> code.getInterpret();
                default -> code.getSummarize();
            };
            String base = "orthoflow.voice.rate-limit." + route + ".";
            assertThat(yml.getProperty(base + "per-minute")).as(route).isEqualTo(String.valueOf(limit.getPerMinute()));
            assertThat(yml.getProperty(base + "per-hour")).as(route).isEqualTo(String.valueOf(limit.getPerHour()));
            assertThat(yml.getProperty(base + "concurrent")).as(route).isEqualTo(String.valueOf(limit.getConcurrent()));
        }
    }
}
