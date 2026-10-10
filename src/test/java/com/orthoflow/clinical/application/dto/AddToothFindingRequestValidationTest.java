package com.orthoflow.clinical.application.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request used to cap a surface at 24 characters while the column and the
 * service allowed 48, so the four-surface cavity a doctor taps in the picker
 * ("mesial-occlusal-distal-buccal", 29) was refused at the door.
 */
class AddToothFindingRequestValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private AddToothFindingRequest request(String surface) {
        AddToothFindingRequest request = new AddToothFindingRequest();
        request.setFindingCode("filling_required");
        request.setSource("manual");
        request.setSurface(surface);
        return request;
    }

    @Test
    void everySurfaceTheFiveZonePickerCanProduceIsAccepted() {
        assertThat(validator.validate(request("mesial-occlusal-distal-buccal"))).isEmpty();
        assertThat(validator.validate(request("mesial-occlusal-distal-buccal-lingual"))).isEmpty();
    }

    @Test
    void aSurfaceLongerThanTheColumnIsRefused() {
        assertThat(validator.validate(request("a".repeat(49)))).isNotEmpty();
    }
}
