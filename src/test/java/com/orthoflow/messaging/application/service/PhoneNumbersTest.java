package com.orthoflow.messaging.application.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class PhoneNumbersTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0661-123456|212661123456",
            "+212 661 123 456|212661123456",
            "00212661123456|212661123456",
            "661123456|212661123456",
            "+33 6 12 34 56 78|33612345678"
    })
    void normalisesToInternationalDigits(String raw, String expected) {
        assertThat(PhoneNumbers.toInternationalDigits(raw, "212")).isEqualTo(expected);
    }

    @Test
    void rejectsWhatCannotBeAPhoneNumber() {
        assertThat(PhoneNumbers.toInternationalDigits("12345", "212")).isNull();
        assertThat(PhoneNumbers.toInternationalDigits("abc", "212")).isNull();
        assertThat(PhoneNumbers.toInternationalDigits(null, "212")).isNull();
    }
}
