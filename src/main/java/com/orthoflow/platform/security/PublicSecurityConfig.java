package com.orthoflow.platform.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * The unauthenticated surface, on a chain of its own so nothing about it can
 * loosen the main chain's deny-by-default policy.
 *
 * <ul>
 *   <li>{@code /public/**} — online booking, self-registration, satisfaction
 *       surveys. No session, no JWT; authority is a random, single-purpose,
 *       expiring token checked by the endpoint itself, behind a per-IP
 *       throttle.</li>
 *   <li>{@code /webhooks/**} — callbacks from services (the WhatsApp bridge).
 *       No throttle: their authority is an HMAC signature the endpoint
 *       verifies.</li>
 * </ul>
 *
 * Anything else under those prefixes that is not a POST or GET is refused.
 */
@Configuration
public class PublicSecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain publicFilterChain(HttpSecurity http, CorsConfigurationSource corsConfigurationSource,
            @Value("${orthoflow.public.rate-limit.reads:120}") int reads,
            @Value("${orthoflow.public.rate-limit.writes:20}") int writes,
            @Value("${orthoflow.public.rate-limit.window-seconds:300}") long windowSeconds) throws Exception {
        http
            .securityMatcher("/public/**", "/webhooks/**")
            .cors(cors -> cors.configurationSource(corsConfigurationSource))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(rules -> rules
                    .requestMatchers(HttpMethod.GET, "/public/**").permitAll()
                    .requestMatchers(HttpMethod.POST, "/public/**", "/webhooks/**").permitAll()
                    .anyRequest().denyAll())
            .addFilterBefore(new PublicRateLimitFilter(reads, writes, windowSeconds), UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
