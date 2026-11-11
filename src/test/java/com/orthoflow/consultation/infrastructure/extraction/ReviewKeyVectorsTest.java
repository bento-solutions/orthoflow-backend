package com.orthoflow.consultation.infrastructure.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * The keys of the review's items are built twice: here, when the model's reading
 * is parsed, and in the browser, when the doctor dictates the same thing with the
 * wake word. If the two disagreed, an allergy dictated and then read from the
 * conversation would be shown twice. The vectors are shared with the frontend's
 * spec (consultation-draft.review-key.spec.ts).
 */
class ReviewKeyVectorsTest {

    private static JsonNode vectors() throws IOException {
        try (InputStream in = ReviewKeyVectorsTest.class.getResourceAsStream("/consultation/review-key-vectors.json")) {
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void theTextIsNormalisedAsTheBrowserNormalisesIt() throws IOException {
        for (JsonNode vector : vectors().path("text")) {
            assertThat(TranscriptText.normalize(vector.path("text").asText()))
                    .as(vector.path("text").asText())
                    .isEqualTo(vector.path("normalized").asText());
        }
    }

    @Test
    void theTeethAreListedAsTheBrowserListsThem() throws IOException {
        for (JsonNode vector : vectors().path("teeth")) {
            String teeth = ConsultationDraftParser.teeth(vector.path("teeth").asText());
            assertThat(teeth == null ? "" : teeth)
                    .as(vector.path("teeth").asText())
                    .isEqualTo(vector.path("normalized").asText());
        }
    }
}
