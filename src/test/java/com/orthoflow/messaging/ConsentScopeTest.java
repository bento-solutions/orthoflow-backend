package com.orthoflow.messaging;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.messaging.application.service.ConsentService;
import com.orthoflow.messaging.domain.model.MessageChannel;
import com.orthoflow.testsupport.PostgresTestSupport;
import com.orthoflow.testsupport.SpringDbTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Consent is reached by native SQL, which the tenant filter does not cover: the service checks the clinic itself. */
class ConsentScopeTest extends SpringDbTest {

    @Autowired
    private ConsentService consent;

    @Test
    void anotherClinicCanNeitherReadNorChangeAPatientsConsent() {
        JdbcTemplate jdbc = PostgresTestSupport.jdbc();
        UUID mine = PostgresTestSupport.newPractice(jdbc);
        UUID theirs = PostgresTestSupport.newPractice(jdbc);
        UUID patient = PostgresTestSupport.patient(jdbc, mine, "Nadia", "Tazi", "1990-01-01", "0612345678", null);
        signInTo(mine);
        consent.recordFor(mine, patient, MessageChannel.WHATSAPP, true, "STAFF");

        signInTo(theirs);
        assertThatThrownBy(() -> consent.forPatientOf(theirs, patient)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> consent.recordFor(theirs, patient, MessageChannel.WHATSAPP, false, "STAFF")).isInstanceOf(NotFoundException.class);

        signInTo(mine);
        assertThat(consent.forPatientOf(mine, patient)).containsEntry(MessageChannel.WHATSAPP, true);
    }
}
