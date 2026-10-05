package com.orthoflow.messaging.application.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateRendererTest {

    @Test
    void substitutesPlaceholders() {
        assertThat(TemplateRenderer.render("Bonjour {{patientName}}, le {{ date }}", Map.of("patientName", "Sara", "date", "12/11")))
                .isEqualTo("Bonjour Sara, le 12/11");
    }

    @Test
    void anUnknownPlaceholderStaysVisibleSoATypoShowsInThePreview() {
        assertThat(TemplateRenderer.render("Hi {{nmae}}", Map.of("name", "Sara"))).isEqualTo("Hi {{nmae}}");
    }

    @Test
    void aValueWithRegexCharactersIsInsertedLiterally() {
        assertThat(TemplateRenderer.render("Total {{amount}}", Map.of("amount", "$1 500,00 \\ x"))).isEqualTo("Total $1 500,00 \\ x");
    }

    @Test
    void doesNotEvaluateExpressions() {
        assertThat(TemplateRenderer.render("{{ 1+1 }} {{T(java.lang.Runtime)}}", Map.of())).isEqualTo("{{ 1+1 }} {{T(java.lang.Runtime)}}");
    }

    @Test
    void nullTemplateStaysNull() {
        assertThat(TemplateRenderer.render(null, Map.of())).isNull();
    }
}
