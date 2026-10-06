package com.orthoflow.retrocession;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.common.security.CurrentUserProvider;
import com.orthoflow.retrocession.application.service.RetrocessionAccess;
import com.orthoflow.team.application.service.PractitionerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What a colleague is paid is nobody else's business: a viewer without manage rights sees only their own. */
class RetrocessionAccessTest {

    private final UUID user = UUID.randomUUID();
    private final UUID me = UUID.randomUUID();
    private CurrentUserProvider currentUser;
    private PractitionerService practitioners;
    private RetrocessionAccess access;

    @BeforeEach
    void setUp() {
        currentUser = mock(CurrentUserProvider.class);
        practitioners = mock(PractitionerService.class);
        when(currentUser.requireUserId()).thenReturn(user);
        access = new RetrocessionAccess(currentUser, practitioners);
    }

    @Test
    void aManagerSeesEveryoneAndMayAskForAnyone() {
        when(currentUser.hasAuthority(Permission.RETROCESSION_MANAGE.name())).thenReturn(true);
        UUID other = UUID.randomUUID();

        assertThat(access.scope()).isNull();
        assertThat(access.narrow(null)).isNull();
        assertThat(access.narrow(other)).isEqualTo(other);
    }

    @Test
    void aViewerIsForcedToTheirOwnFigures() {
        when(practitioners.findIdByUser(user)).thenReturn(Optional.of(me));

        assertThat(access.narrow(null)).isEqualTo(me);
        assertThat(access.narrow(me)).isEqualTo(me);
    }

    @Test
    void aViewerAskingForAColleagueIsRefused() {
        when(practitioners.findIdByUser(user)).thenReturn(Optional.of(me));

        assertThatThrownBy(() -> access.narrow(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> access.check(UUID.randomUUID())).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aViewerWhoIsNotAPractitionerSeesNothing() {
        when(practitioners.findIdByUser(user)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.narrow(null)).isInstanceOf(AccessDeniedException.class);
    }
}
