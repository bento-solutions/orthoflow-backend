package com.orthoflow.voice.infrastructure.nlu;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The response schema decides what a model is able to tell us, not merely how
 * it is formatted: structured output emits only the properties the schema
 * names. An entity missing here cannot be returned at all, and the failure is
 * silent — the intent arrives with an empty argument map and the assistant
 * says it understood while recording nothing.
 */
class NluPromptBuilderTest {

    private final NluPromptBuilder builder = new NluPromptBuilder();

    @SuppressWarnings("unchecked")
    private Map<String, Object> entityProperties() {
        Map<String, Object> properties = (Map<String, Object>) builder.responseSchema().get("properties");
        Map<String, Object> entities = (Map<String, Object>) properties.get("entities");
        return (Map<String, Object>) entities.get("properties");
    }

    @Test
    void declaresEveryArgumentTheCommandSetTakes() {
        assertThat(entityProperties()).containsKeys(
                "fdi", "findings", "codes",        // the chart commands
                "category", "content",             // a clinical note
                "label", "detail",                 // medical and dental history
                "substance", "reaction", "severity", "note",
                "when");                           // a follow-up
    }

    @Test
    @SuppressWarnings("unchecked")
    void letsOneUtteranceCarrySeveralFindingsWithSurfaceAndSeverity() {
        Map<String, Object> findings = (Map<String, Object>) entityProperties().get("findings");
        assertThat(findings).containsEntry("type", "array");

        Map<String, Object> item = (Map<String, Object>) findings.get("items");
        assertThat((Map<String, Object>) item.get("properties")).containsKeys("code", "surface", "severity", "note");
        // The code is the only part that must be there; everything else is
        // detail the dentist may or may not have said.
        assertThat((List<String>) item.get("required")).containsExactly("code");
    }

    @Test
    @SuppressWarnings("unchecked")
    void requiresAnAnswerToEveryTopLevelQuestion() {
        // Including clarification: a model that omits it leaves the caller
        // unable to tell "no intent" from "no answer".
        assertThat((List<String>) builder.responseSchema().get("required"))
                .containsExactlyInAnyOrder("intent", "entities", "confidence", "clarification");
    }
}
