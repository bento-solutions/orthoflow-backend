package com.orthoflow.platform.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;

/** The public pages are open to the world, so a form that creates work for staff is held to a far lower ceiling than reading. */
class PublicRateLimitFilterTest {

    private MockHttpServletResponse call(PublicRateLimitFilter filter, String method, String path, String ip, FilterChain chain) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1" + path);
        request.setServletPath(path);
        request.setRemoteAddr(ip);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);
        return response;
    }

    @Test
    void writesAreCappedMuchLowerThanReads() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(5, 2, 60);
        FilterChain chain = mock(FilterChain.class);

        assertThat(call(filter, "POST", "/public/book/x/requests", "10.0.0.1", chain).getStatus()).isEqualTo(200);
        assertThat(call(filter, "POST", "/public/book/x/requests", "10.0.0.1", chain).getStatus()).isEqualTo(200);
        MockHttpServletResponse limited = call(filter, "POST", "/public/book/x/requests", "10.0.0.1", chain);

        assertThat(limited.getStatus()).isEqualTo(429);
        assertThat(limited.getHeader("Retry-After")).isNotNull();
        for (int i = 0; i < 5; i++) {
            assertThat(call(filter, "GET", "/public/book/x", "10.0.0.1", chain).getStatus()).isEqualTo(200);
        }
        assertThat(call(filter, "GET", "/public/book/x", "10.0.0.1", chain).getStatus()).isEqualTo(429);
    }

    @Test
    void oneClientsBurstDoesNotLockOutAnother() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(5, 1, 60);
        FilterChain chain = mock(FilterChain.class);

        call(filter, "POST", "/public/register/x", "10.0.0.1", chain);
        assertThat(call(filter, "POST", "/public/register/x", "10.0.0.1", chain).getStatus()).isEqualTo(429);
        assertThat(call(filter, "POST", "/public/register/x", "10.0.0.2", chain).getStatus()).isEqualTo(200);
    }

    @Test
    void theForwardedClientAddressIsWhatIsCounted() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(5, 1, 60);
        FilterChain chain = mock(FilterChain.class);
        for (String client : new String[]{"203.0.113.9", "203.0.113.10"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/public/survey/x");
            request.setServletPath("/public/survey/x");
            request.setRemoteAddr("172.18.0.2");
            request.addHeader("X-Forwarded-For", client + ", 172.18.0.2");
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, chain);
            assertThat(response.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void webhooksAreNotThrottledBecauseTheirAuthorityIsASignature() throws Exception {
        PublicRateLimitFilter filter = new PublicRateLimitFilter(1, 1, 60);
        FilterChain chain = mock(FilterChain.class);

        for (int i = 0; i < 10; i++) {
            assertThat(call(filter, "POST", "/webhooks/whatsapp", "10.0.0.1", chain).getStatus()).isEqualTo(200);
        }
        verify(chain, times(10)).doFilter(any(), any());
    }
}
