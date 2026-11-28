package com.orthoflow.reminders;

import com.orthoflow.reminders.application.service.AppointmentReplyListener;
import com.orthoflow.reminders.application.service.AppointmentReplyListener.ReplyChoice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

class AppointmentReplyTest {

    @ParameterizedTest
    @ValueSource(strings = {"1", " 1 ", "1.", "1 - oui", "Oui", "OUI !", "yes", "ok", "نعم", "Confirmé", "à l'heure", "on time", "في الموعد"})
    void oneOrOnTimeFreeTextConfirmsOnTime(String reply) {
        assertThat(AppointmentReplyListener.interpretChoice(reply)).isEqualTo(ReplyChoice.CONFIRMED_ON_TIME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"2", " 2 ", "2.", "2 - retard", "je serai en retard", "un peu de retard", "running late", "late", "متأخر"})
    void twoOrLateFreeTextConfirmsButFlagsLate(String reply) {
        assertThat(AppointmentReplyListener.interpretChoice(reply)).isEqualTo(ReplyChoice.CONFIRMED_LATE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"3", " 3 ", "3.", "non", "Non.", "NO", "لا", "annuler", "Annulé", "je ne peux pas venir", "cannot come", "cancel"})
    void threeOrCannotComeCancelsAppointment(String reply) {
        assertThat(AppointmentReplyListener.interpretChoice(reply)).isEqualTo(ReplyChoice.CANNOT_COME);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bonjour", "12", "oui mais plutôt jeudi", "", "😊"})
    void ambiguousRepliesAreLeftForStaff(String reply) {
        assertThat(AppointmentReplyListener.interpretChoice(reply)).isNull();
    }

    @Test
    void nullIsNotAnAnswer() {
        assertThat(AppointmentReplyListener.interpretChoice(null)).isNull();
    }
}
