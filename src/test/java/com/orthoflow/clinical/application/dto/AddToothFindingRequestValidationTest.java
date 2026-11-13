package com.orthoflow.clinical.application.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The request used to cap a surface at 24 characters while the column and the
 * service allowed 48, so the four-surface cavity a doctor taps in the picker
 * ("mesial-occlusal-distal-buccal", 29) was refused at the door. The column now holds
 * 64, for the roots a back tooth's finding can name.
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
    void theRootsOfABackToothFitWithTheFacesOfItsCrown() {
        // Up to five parts are joined: the longest of them, with every root named, is 53 characters.
        assertThat(validator.validate(request("occlusal-mesialroot-distalroot-buccalroot-lingualroot"))).isEmpty();
    }

    @Test
    void aSurfaceLongerThanTheColumnIsRefused() {
        assertThat(validator.validate(request("a".repeat(64)))).isEmpty();
        assertThat(validator.validate(request("a".repeat(65)))).isNotEmpty();
    }

    @Test
    void aCertaintyIsAtMostAsLongAsItsColumn() {
        AddToothFindingRequest request = request(null);
        request.setCertainty("SUSPECTED");
        assertThat(validator.validate(request)).isEmpty();
        request.setCertainty("x".repeat(13));
        assertThat(validator.validate(request)).isNotEmpty();
    }
}
