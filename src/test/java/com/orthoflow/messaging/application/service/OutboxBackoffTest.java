package com.orthoflow.messaging.application.service;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxBackoffTest {

    @Test
    void doublesFromTwoMinutes() {
        assertThat(OutboxProcessor.backoff(1)).isEqualTo(Duration.ofMinutes(2));
        assertThat(OutboxProcessor.backoff(2)).isEqualTo(Duration.ofMinutes(4));
        assertThat(OutboxProcessor.backoff(3)).isEqualTo(Duration.ofMinutes(8));
    }

    @Test
    void neverWaitsMoreThanSixHours() {
        assertThat(OutboxProcessor.backoff(9)).isEqualTo(Duration.ofHours(6));
        assertThat(OutboxProcessor.backoff(50)).isEqualTo(Duration.ofHours(6));
    }
}
