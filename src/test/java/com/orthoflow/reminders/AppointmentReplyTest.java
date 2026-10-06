package com.orthoflow.reminders;

import com.orthoflow.reminders.application.service.AppointmentReplyListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentReplyTest {

    private static Boolean interpret(String body) throws Exception {
        Method m = AppointmentReplyListener.class.getDeclaredMethod("interpret", String.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, body);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", " 1 ", "1.", "Oui", "OUI !", "yes", "ok", "نعم", "Confirmé", "confirmer"})
    void aYesConfirms(String reply) throws Exception {
        assertThat(interpret(reply)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", "non", "Non.", "NO", "لا", "annuler", "Annulé"})
    void aNoCancels(String reply) throws Exception {
        assertThat(interpret(reply)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bonjour", "je serai en retard", "12", "oui mais plutôt jeudi", "", "😊"})
    void anythingElseIsLeftForStaff(String reply) throws Exception {
        assertThat(interpret(reply)).isNull();
    }

    @Test
    void nullIsNotAnAnswer() throws Exception {
        assertThat(interpret(null)).isNull();
    }
}
