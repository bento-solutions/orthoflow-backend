package com.orthoflow.voice.infrastructure.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.orthoflow.common.security.AuthenticatedUser;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

class VoiceRateLimitFilterTest {

    private final AuthenticatedUser doctor = new AuthenticatedUser(UUID.randomUUID(), "d@cabinet.ma", "DOCTOR");
    private VoiceRateLimitProperties props;
    private VoiceRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        props = new VoiceRateLimitProperties();
        props.setTranscribe(new VoiceRateLimitProperties.Limit(2, 100, 0));
        filter = new VoiceRateLimitFilter(new VoiceRateLimiter(props));
        signIn(doctor);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private static void signIn(AuthenticatedUser user) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                user, null, List.of(new SimpleGrantedAuthority("ROLE_" + user.role()))));
    }

    private static MockHttpServletRequest post(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + path);
        request.setContextPath("/api/v1");
        return request;
    }

    /** Runs one request through the filter; returns the response. */
    private MockHttpServletResponse run(MockHttpServletRequest request, AtomicInteger reachedHandler)
            throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                reachedHandler.incrementAndGet();
            }
        });
        return response;
    }

    @Test
    void readingAConsultationIsThrottledOnItsOwnRoute() throws Exception {
        props.setExtract(new VoiceRateLimitProperties.Limit(1, 100, 0));
        filter = new VoiceRateLimitFilter(new VoiceRateLimiter(props));
        String path = "/consultations/" + UUID.randomUUID() + "/extract";
        AtomicInteger reached = new AtomicInteger();

        run(post(path), reached);
        MockHttpServletResponse refused = run(post(path), reached);

        assertThat(reached.get()).isEqualTo(1);
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(refused.getContentAsString()).contains("consultation reading");
    }

    @Test
    void otherConsultationRoutesAreNotThrottledBecauseTheyCostNothing() throws Exception {
        props.setExtract(new VoiceRateLimitProperties.Limit(1, 100, 0));
        filter = new VoiceRateLimitFilter(new VoiceRateLimiter(props));
        AtomicInteger reached = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            run(post("/consultations/" + UUID.randomUUID() + "/end"), reached);
        }

        assertThat(reached.get()).isEqualTo(5);
    }

    @Test
    void turnsTheBurstAwayWith429AndRetryAfterBeforeTheHandlerRuns() throws Exception {
        AtomicInteger reached = new AtomicInteger();
        run(post("/voice/transcribe"), reached);
        run(post("/voice/transcribe"), reached);
        MockHttpServletResponse refused = run(post("/voice/transcribe"), reached);

        assertThat(reached.get()).isEqualTo(2);
        assertThat(refused.getStatus()).isEqualTo(429);
        assertThat(Long.parseLong(refused.getHeader("Retry-After"))).isBetween(1L, 60L);
        assertThat(refused.getContentType()).startsWith("application/problem+json");
        assertThat(refused.getContentAsString())
                .contains("\"status\":429")
                .contains("speech transcription")
                .contains("/api/v1/voice/transcribe");
    }

    @Test
    void throttlesInterpretationAndTheSummaryButNotTheRestOfTheVoiceApi() throws Exception {
        props.setInterpret(new VoiceRateLimitProperties.Limit(1, 100, 0));
        props.setSummarize(new VoiceRateLimitProperties.Limit(1, 100, 0));
        AtomicInteger reached = new AtomicInteger();
        String session = UUID.randomUUID().toString();

        run(post("/voice/interpret"), reached);
        assertThat(run(post("/voice/interpret"), reached).getStatus()).isEqualTo(429);

        run(post("/voice/sessions/" + session + "/summarize"), reached);
        assertThat(run(post("/voice/sessions/" + session + "/summarize"), reached).getStatus()).isEqualTo(429);

        int before = reached.get();
        for (int i = 0; i < 30; i++) {
            assertThat(run(post("/voice/commands"), reached).getStatus()).isEqualTo(200);
            assertThat(run(post("/voice/sessions/" + session + "/commit"), reached).getStatus()).isEqualTo(200);
        }
        assertThat(reached.get() - before).isEqualTo(60);
    }

    @Test
    void readsAreNeverThrottled() throws Exception {
        AtomicInteger reached = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest get = new MockHttpServletRequest("GET", "/api/v1/voice/transcribe");
            get.setContextPath("/api/v1");
            assertThat(run(get, reached).getStatus()).isEqualTo(200);
        }
        assertThat(reached.get()).isEqualTo(10);
    }

    @Test
    void anotherDoctorIsNotAffectedByTheFirstOnesBurst() throws Exception {
        AtomicInteger reached = new AtomicInteger();
        for (int i = 0; i < 5; i++) run(post("/voice/transcribe"), reached);

        signIn(new AuthenticatedUser(UUID.randomUUID(), "other@cabinet.ma", "DOCTOR"));
        assertThat(run(post("/voice/transcribe"), reached).getStatus()).isEqualTo(200);
    }

    @Test
    void anUnauthenticatedCallIsLeftForTheSecurityRulesToAnswer() throws Exception {
        SecurityContextHolder.clearContext();
        AtomicInteger reached = new AtomicInteger();
        for (int i = 0; i < 10; i++) {
            assertThat(run(post("/voice/transcribe"), reached).getStatus()).isEqualTo(200);
        }
        assertThat(reached.get()).isEqualTo(10);
    }

    @Test
    void freesTheConcurrencySlotEvenWhenTheRequestFails() {
        props.setTranscribe(new VoiceRateLimitProperties.Limit(100, 1000, 1));

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> filter.doFilter(post("/voice/transcribe"), new MockHttpServletResponse(),
                    new MockFilterChain() {
                        @Override
                        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                            throw new IllegalStateException("vendor exploded");
                        }
                    })).isInstanceOf(IllegalStateException.class);
        }
        // If the slot had leaked, the second and third would have been answered 429 instead.
    }
}
