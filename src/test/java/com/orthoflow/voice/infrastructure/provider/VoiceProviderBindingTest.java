package com.orthoflow.voice.infrastructure.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * The data-protection switches, bound the way Spring binds them at startup, from
 * the real configuration files. They used to sit at {@code orthoflow.voice.*}
 * while the class reads {@code orthoflow.voice.providers.*}: nothing bound them,
 * so the dev profile's "not required" never applied and {@code VOICE_ALLOWED_REGIONS}
 * was ignored on every server. A test of the class alone could not see it.
 */
class VoiceProviderBindingTest {

    private static VoiceProviderProperties bind(Map<String, Object> environment, String... profiles) throws IOException {
        MutablePropertySources sources = new MutablePropertySources();
        sources.addFirst(new MapPropertySource("environment", new LinkedHashMap<>(environment)));
        // A later profile file overrides the base file, as Spring orders them.
        for (String profile : profiles) {
            for (PropertySource<?> source : new YamlPropertySourceLoader()
                    .load(profile, new ClassPathResource("application-" + profile + ".yml"))) {
                sources.addLast(source);
            }
        }
        for (PropertySource<?> source
                : new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))) {
            sources.addLast(source);
        }
        Binder binder = new Binder(ConfigurationPropertySources.from(sources), new PropertySourcesPlaceholdersResolver(sources));
        return binder.bind("orthoflow.voice.providers", Bindable.ofInstance(new VoiceProviderProperties()))
                .orElseGet(VoiceProviderProperties::new);
    }

    @Test
    void dataProtectionIsRequiredByDefault() throws IOException {
        VoiceProviderProperties bound = bind(Map.of());

        assertThat(bound.isRequireDataProtection()).isTrue();
        assertThat(bound.getAllowedRegions()).isEmpty();
    }

    @Test
    void theDevProfileDoesNotRequireIt() throws IOException {
        assertThat(bind(Map.of(), "dev").isRequireDataProtection()).isFalse();
    }

    @Test
    void theEnvironmentVariablesAreRead() throws IOException {
        VoiceProviderProperties bound = bind(Map.of(
                "VOICE_ALLOWED_REGIONS", "EU,MA",
                "VOICE_REQUIRE_DATA_PROTECTION", "false"));

        assertThat(bound.getAllowedRegions()).containsExactly("EU", "MA");
        assertThat(bound.isRequireDataProtection()).isFalse();
    }

    @Test
    void theVendorSettingsStillBindBesideThem() throws IOException {
        VoiceProviderProperties bound = bind(Map.of("VOICE_GROQ_DATA_PROTECTED", "true"));

        assertThat(bound.getGroq().isDataProtected()).isTrue();
        assertThat(bound.getAssemblyai().getRegion()).isEqualTo("EU");
    }
}
