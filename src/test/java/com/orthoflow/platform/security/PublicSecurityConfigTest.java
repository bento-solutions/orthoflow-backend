package com.orthoflow.platform.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

/**
 * The public chain on its own: /public and /webhooks answer to anyone (and so reach
 * the dispatcher, which here has no handler: 404), and nothing else about them is
 * open — no PUT, no DELETE, no other prefix. Everything not under those two
 * prefixes belongs to the main chain, whose deny-by-default is tested separately.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {PublicSecurityConfig.class, PublicSecurityConfigTest.Mvc.class})
@WebAppConfiguration
@TestPropertySource(properties = {"orthoflow.public.rate-limit.reads=1000", "orthoflow.public.rate-limit.writes=1000"})
class PublicSecurityConfigTest {

    @RestController
    static class Noop {
    }

    @Configuration
    @EnableWebMvc
    @org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
    static class Mvc {
        @Bean
        Noop noop() {
            return new Noop();
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", new CorsConfiguration());
            return source;
        }
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private int status(HttpMethod method, String path) throws Exception {
        return mvc.perform(request(method, path)).andReturn().getResponse().getStatus();
    }

    @Test
    void anonymousReadsAndFormPostsReachTheEndpoint() throws Exception {
        assertThat(status(HttpMethod.GET, "/public/book/sometoken")).isEqualTo(404);
        assertThat(status(HttpMethod.POST, "/public/book/sometoken/requests")).isEqualTo(404);
        assertThat(status(HttpMethod.POST, "/public/register/sometoken")).isEqualTo(404);
        assertThat(status(HttpMethod.POST, "/webhooks/whatsapp")).isEqualTo(404);
    }

    @Test
    void noOtherMethodIsOpenOnThePublicSurface() throws Exception {
        for (HttpMethod method : new HttpMethod[]{HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH}) {
            assertThat(status(method, "/public/book/sometoken/requests")).isIn(401, 403);
            assertThat(status(method, "/webhooks/whatsapp")).isIn(401, 403);
        }
        assertThat(status(HttpMethod.GET, "/webhooks/whatsapp")).isIn(401, 403);
    }
}
