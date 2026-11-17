package com.orthoflow.common.exception;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** A document refused for an incomplete profile tells the app which fields to send the person to. */
class IncompleteProfileProblemTest {

    @Test
    void theProblemCarriesItsCodeAndTheMissingFields() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/invoices/1/pdf");

        var problem = new GlobalExceptionHandler()
                .handleIncompleteProfile(new IncompleteProfileException(List.of("ice", "taxId")), request);

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail()).contains("ice, taxId");
        assertThat(problem.getProperties()).containsEntry("code", "CLINIC_PROFILE_INCOMPLETE")
                .containsEntry("missing", List.of("ice", "taxId"));
    }
}
