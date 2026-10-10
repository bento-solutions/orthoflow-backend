package com.orthoflow.platform.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The OpenAPI document is the source of the frontend's types, so it has to say what the API really
 * does. Two things went wrong before: times of day described as objects, and DTOs that share a
 * simple name (View, Request, Row...) collapsing into one schema, which gave the others the wrong type.
 */
@AutoConfigureMockMvc
class OpenApiDocumentTest extends SpringDbTest {

    /** Names too generic to identify one DTO. A record that wants one of these needs @Schema(name = ...). */
    private static final Set<String> TOO_GENERIC = Set.of("Create", "Dashboard", "Day", "Entry", "Invite", "Kpis", "Person", "Preview",
            "PublicInfo", "Reorder", "Request", "Response", "Result", "Row", "Settings", "Summary", "View");

    @Autowired
    private MockMvc mockMvc;

    private JsonNode document() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        return new ObjectMapper().readTree(json);
    }

    /**
     * With {@code -Dopenapi.dump=<file>}, writes the document there, for regenerating the
     * frontend's types without starting a server ({@code npx openapi-typescript <file> -o ...}).
     */
    @Test
    void theDocumentCanBeWrittenOutForTheFrontendsTypes() throws Exception {
        String target = System.getProperty("openapi.dump");
        if (target != null) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(target),
                    mockMvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString());
        }
        assertThat(document().at("/components/schemas").size()).isGreaterThan(100);
    }

    @Test
    void aTimeOfDayIsDescribedAsTheStringItIsOnTheWire() throws Exception {
        JsonNode openTime = document().at("/components/schemas/OpeningDay/properties/openTime");
        assertThat(openTime.path("type").asText()).isEqualTo("string");
        assertThat(openTime.has("properties")).as("not an object with hour and minute").isFalse();
    }

    @Test
    void noSchemaIsNamedSoGenericallyThatTwoDtosCouldShareIt() throws Exception {
        Set<String> names = new TreeSet<>();
        document().at("/components/schemas").fieldNames().forEachRemaining(names::add);
        names.retainAll(TOO_GENERIC);
        assertThat(names).isEmpty();
    }
}
