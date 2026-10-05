package com.orthoflow.publicapi;

import com.orthoflow.common.exception.NotFoundException;
import com.orthoflow.publicapi.application.service.PublicLinkService;
import com.orthoflow.publicapi.domain.model.PublicLink;
import com.orthoflow.publicapi.domain.model.PublicLinkPurpose;
import com.orthoflow.publicapi.infrastructure.PublicLinkJpaRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PublicLinkServiceTest {

    private PublicLinkJpaRepository repo;
    private PublicLinkService service;
    private final List<PublicLink> saved = new ArrayList<>();
    private final UUID practice = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = mock(PublicLinkJpaRepository.class);
        when(repo.save(any(PublicLink.class))).thenAnswer(inv -> {
            PublicLink l = inv.getArgument(0);
            if (l.getId() == null) l.setId(UUID.randomUUID());
            saved.add(l);
            return l;
        });
        service = new PublicLinkService(repo, "a-server-secret-for-link-derivation");
    }

    @Test
    void storesOnlyAHashOfTheTokenNeverTheToken() {
        PublicLinkService.Issued issued = service.issue(practice, PublicLinkPurpose.SURVEY, "APPOINTMENT", UUID.randomUUID(),
                Duration.ofDays(7), 1, null);

        PublicLink stored = saved.get(0);
        assertThat(stored.getTokenHash()).hasSize(64).isNotEqualTo(issued.token());
        assertThat(stored.getTokenHash()).doesNotContain(issued.token());
        assertThat(issued.token()).hasSizeGreaterThanOrEqualTo(40);
    }

    @Test
    void resolvesAValidTokenForItsOwnPurposeOnly() {
        PublicLinkService.Issued issued = service.issue(practice, PublicLinkPurpose.BOOKING, null, null, null, null, null);
        PublicLink stored = saved.get(0);
        when(repo.findByTokenHash(stored.getTokenHash())).thenReturn(Optional.of(stored));

        assertThat(service.resolve(issued.token(), PublicLinkPurpose.BOOKING)).isSameAs(stored);
        assertThatThrownBy(() -> service.resolve(issued.token(), PublicLinkPurpose.SURVEY)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void everyKindOfBadTokenAnswersTheSameNotFound() {
        PublicLink expired = PublicLink.builder().id(UUID.randomUUID()).purpose(PublicLinkPurpose.SURVEY)
                .expiresAt(OffsetDateTime.now().minusMinutes(1)).tokenHash("x").build();
        PublicLink revoked = PublicLink.builder().id(UUID.randomUUID()).purpose(PublicLinkPurpose.SURVEY)
                .revokedAt(OffsetDateTime.now().minusMinutes(1)).tokenHash("y").build();
        PublicLink usedUp = PublicLink.builder().id(UUID.randomUUID()).purpose(PublicLinkPurpose.SURVEY)
                .maxUses(1).uses(1).tokenHash("z").build();
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.empty());

        for (PublicLink link : List.of(expired, revoked, usedUp)) {
            when(repo.findByTokenHash(anyString())).thenReturn(Optional.of(link));
            assertThatThrownBy(() -> service.resolve("a-token-that-is-long-enough-to-pass-length", PublicLinkPurpose.SURVEY))
                    .isInstanceOf(NotFoundException.class).hasMessage("This link is not valid");
        }
        when(repo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.resolve("an-unknown-token-that-is-long-enough", PublicLinkPurpose.SURVEY))
                .isInstanceOf(NotFoundException.class).hasMessage("This link is not valid");
        assertThatThrownBy(() -> service.resolve("short", PublicLinkPurpose.SURVEY)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.resolve(null, PublicLinkPurpose.SURVEY)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void theSharedTokenIsStableUntilRotatedAndRotationRetiresTheOldOne() {
        PublicLink first = PublicLink.builder().id(UUID.randomUUID()).practiceId(practice).purpose(PublicLinkPurpose.BOOKING)
                .subjectType(PublicLink.SHARED).rotation(0).tokenHash("-").build();
        when(repo.findByPracticeIdAndPurposeAndSubjectTypeAndRevokedAtIsNull(practice, PublicLinkPurpose.BOOKING, PublicLink.SHARED))
                .thenReturn(List.of(first));

        String a = service.sharedToken(practice, PublicLinkPurpose.BOOKING);
        String b = service.sharedToken(practice, PublicLinkPurpose.BOOKING);
        assertThat(a).isEqualTo(b);

        String rotated = service.rotateShared(practice, PublicLinkPurpose.BOOKING, UUID.randomUUID());

        assertThat(rotated).isNotEqualTo(a);
        assertThat(first.getRevokedAt()).isNotNull();
        ArgumentCaptor<PublicLink> created = ArgumentCaptor.forClass(PublicLink.class);
        verify(repo, org.mockito.Mockito.atLeastOnce()).save(created.capture());
        assertThat(created.getValue().getRotation()).isEqualTo(1);
    }

    @Test
    void aUsedUpOrRevokedLinkIsNotUsable() {
        assertThat(PublicLink.builder().maxUses(2).uses(1).build().isUsable(OffsetDateTime.now())).isTrue();
        assertThat(PublicLink.builder().maxUses(2).uses(2).build().isUsable(OffsetDateTime.now())).isFalse();
        assertThat(PublicLink.builder().build().isUsable(OffsetDateTime.now())).isTrue();
    }

    @Test
    void consumeIsOneAtomicUpdate() {
        UUID id = UUID.randomUUID();
        when(repo.consume(eq(id))).thenReturn(1, 0);

        assertThat(service.consume(id)).isTrue();
        assertThat(service.consume(id)).isFalse();
    }
}
