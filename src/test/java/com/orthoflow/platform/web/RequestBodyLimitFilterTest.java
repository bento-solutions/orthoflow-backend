package com.orthoflow.platform.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestBodyLimitFilterTest {

    private final RequestBodyLimitFilter filter = new RequestBodyLimitFilter();

    private static MockHttpServletRequest post(String path, byte[] body, boolean declareLength) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1" + path) {
            @Override
            public long getContentLengthLong() {
                return declareLength ? super.getContentLengthLong() : -1;
            }
        };
        request.setServletPath(path);
        request.setContentType("application/json");
        request.setContent(body);
        return request;
    }

    @Test
    void aDeclaredBodyOverTheLimitIsRefusedBeforeAnythingReadsIt() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (req, res) -> {
            throw new AssertionError("the body must not reach the application");
        };

        filter.doFilter(post("/webhooks/whatsapp", new byte[300 * 1024], true), response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
    }

    @Test
    void anUndeclaredBodyStopsAtTheLimitWhileItIsRead() throws Exception {
        FilterChain chain = (req, res) -> req.getInputStream().readAllBytes();

        assertThatThrownBy(() -> filter.doFilter(post("/public/book/x/requests", new byte[65 * 1024], false), new MockHttpServletResponse(), chain))
                .isInstanceOf(IOException.class).hasMessageContaining("64 KB");
    }

    @Test
    void anOrdinaryBodyPassesThrough() throws Exception {
        byte[][] seen = new byte[1][];
        FilterChain chain = (req, res) -> seen[0] = req.getInputStream().readAllBytes();

        filter.doFilter(post("/invoices", "{\"lines\":[]}".getBytes(), true), new MockHttpServletResponse(), chain);

        assertThat(new String(seen[0])).isEqualTo("{\"lines\":[]}");
    }

    @Test
    void theLimitsFollowTheRoute() {
        assertThat(RequestBodyLimitFilter.limitFor("/public/register/x")).isEqualTo(64 * 1024);
        assertThat(RequestBodyLimitFilter.limitFor("/webhooks/whatsapp")).isEqualTo(256 * 1024);
        assertThat(RequestBodyLimitFilter.limitFor("/consultations/abc/end")).isEqualTo(8 * 1024 * 1024);
        assertThat(RequestBodyLimitFilter.limitFor("/invoices")).isEqualTo(2 * 1024 * 1024);
    }
}
