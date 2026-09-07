package com.orthoflow.platform.security;

import com.orthoflow.auth.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * The application's single authorisation policy.
 *
 * <p>This used to live in {@code billing.infrastructure.config} — one feature
 * module owning a rule that governs every other module — and its only rule was
 * {@code anyRequest().authenticated()}. That meant every authenticated session,
 * whatever its role, could void invoices, delete purchase orders and cancel
 * treatment sessions; only {@code clinical}, {@code voice}, {@code compliance},
 * {@code patient} and {@code settings} had added {@code @PreAuthorize} rules of
 * their own, leaving 15 of 22 controllers ungoverned.
 *
 * <p>Two things changed. Policy now lives in {@code platform}, which is the
 * composition root and the only package allowed to depend on every module. And
 * the default is {@link AuthorizeHttpRequestsConfigurer.AuthorizedUrl#denyAll()
 * denyAll} rather than "authenticated": a new endpoint is unreachable until
 * someone adds it to the matrix below, so forgetting to think about
 * authorisation fails loudly in development instead of silently in production.
 *
 * <p>Rules are evaluated top to bottom, first match wins, so they run from most
 * specific to most general. {@code @PreAuthorize} is still the right tool for
 * decisions that depend on the row being touched rather than the path; the
 * annotations already in place stay, and this matrix is the floor beneath them.
 *
 * <p>The whole matrix is asserted endpoint-by-endpoint in
 * {@code SecurityPolicyTest}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@EnableConfigurationProperties(CorsProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    /** Front desk: patients, appointments, stock movements, taking payment. */
    private static final String ASSISTANT = "ASSISTANT";
    /** Clinicians: everything an assistant may do, plus the clinical record. */
    private static final String DOCTOR = "DOCTOR";
    /** Practice owner: the above, plus deletions, settings and compliance. */
    private static final String ADMIN = "ADMIN";

    private static final String[] EVERYONE = { ASSISTANT, DOCTOR, ADMIN };
    private static final String[] CLINICAL = { DOCTOR, ADMIN };

    private final JwtAuthFilter jwtAuthFilter;
    private final AuthRateLimitFilter authRateLimitFilter;
    private final CorsProperties corsProperties;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(SecurityConfig::policy)
            // 401 for "not authenticated", 403 for "wrong role" — both as
            // problem+json. Without this, an unauthenticated call to a
            // protected route returns Spring's default 403 and the frontend
            // never realises the session is gone.
            .exceptionHandling(ex -> ex
                    .authenticationEntryPoint(authenticationEntryPoint)
                    .accessDeniedHandler(accessDeniedHandler))
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(authRateLimitFilter, JwtAuthFilter.class);
        return http.build();
    }

    private static void policy(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry rules) {
        rules
            // ── Framework dispatches ────────────────────────────────────────
            // Under a denyAll default these must be permitted explicitly, or
            // GlobalExceptionHandler's rendering of a 404 is itself authorised
            // and comes back as a 403.
            .dispatcherTypeMatchers(DispatcherType.ERROR, DispatcherType.ASYNC, DispatcherType.FORWARD).permitAll()

            // ── Public ──────────────────────────────────────────────────────
            // /auth/register is open because it bootstraps the first ADMIN on
            // an empty database. When orthoflow.auth.restrict-registration-to-
            // bootstrap is enabled, AuthController itself requires an ADMIN
            // once any user exists; that check cannot move here — it depends
            // on whether the users table is empty, not on the path.
            .requestMatchers(HttpMethod.POST,
                    "/auth/login", "/auth/register", "/auth/forgot-password", "/auth/reset-password").permitAll()
            // Reachable only from inside the Docker network — Traefik proxies
            // /api/v1/** and nothing else (docker-compose.production.yml).
            .requestMatchers("/actuator/**").permitAll()
            // API shape only, no patient data, and disabled outright in prod
            // via springdoc.api-docs.enabled=false.
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()

            // ── Clinical record: clinicians only ────────────────────────────
            // Findings, notes, allergies and medical history are the most
            // sensitive data in the system and are not front-desk business.
            .requestMatchers("/patients/*/clinical-record/**").hasAnyRole(CLINICAL)
            // The chart is readable by the front desk (it drives scheduling and
            // quoting) but only a clinician may change a tooth.
            .requestMatchers(HttpMethod.GET, "/patients/*/dental-chart/**").hasAnyRole(EVERYONE)
            .requestMatchers("/patients/*/dental-chart/**").hasAnyRole(CLINICAL)
            .requestMatchers(HttpMethod.GET, "/clinical/finding-catalog").hasAnyRole(EVERYONE)
            // Voice dictates into the clinical record, so it inherits its floor.
            .requestMatchers("/voice/**").hasAnyRole(CLINICAL)

            // ── Compliance: data-subject rights are the operator's duty ──────
            .requestMatchers("/patients/*/compliance/**").hasRole(ADMIN)

            // ── Treatment sessions (patient-scoped, served by stock) ─────────
            .requestMatchers(HttpMethod.GET, "/patients/treatments").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.GET, "/patients/*/treatments/**").hasAnyRole(EVERYONE)
            // Recording what was done to a patient is a clinical act.
            .requestMatchers("/patients/*/treatments/**").hasAnyRole(CLINICAL)

            // ── Patients ────────────────────────────────────────────────────
            // Erasure is irreversible and is a compliance decision.
            .requestMatchers(HttpMethod.DELETE, "/patients/*/erase").hasRole(ADMIN)
            .requestMatchers(HttpMethod.DELETE, "/patients/*").hasRole(ADMIN)
            .requestMatchers(HttpMethod.POST, "/patients").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.PUT, "/patients/*").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.GET, "/patients", "/patients/*").hasAnyRole(EVERYONE)

            // ── Scheduling ──────────────────────────────────────────────────
            // Wholly front-desk work, including cancellations.
            .requestMatchers("/appointments/**").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.GET, "/scheduling/chairs").hasAnyRole(EVERYONE)

            // ── Billing ─────────────────────────────────────────────────────
            .requestMatchers(HttpMethod.POST, "/invoices/*/payments").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.POST, "/invoices").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.GET, "/invoices", "/invoices/**").hasAnyRole(EVERYONE)

            // ── Practice settings ───────────────────────────────────────────
            // Everyone reads them (currency, logo, letterhead); only the owner
            // changes them.
            .requestMatchers(HttpMethod.GET, "/settings/practice").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.PUT, "/settings/practice").hasRole(ADMIN)

            // ── Reporting: margins and profitability are not front-desk data ─
            .requestMatchers("/stock/analytics/**").hasAnyRole(CLINICAL)

            // ── Treatment invoices (cost/consumption records) ────────────────
            .requestMatchers(HttpMethod.DELETE, "/stock/treatment-invoices/**").hasRole(ADMIN)
            // Finalising creates the patient-facing billing.Invoice (ADR-0005)
            // and cancelling voids it; both are clinical/owner decisions.
            .requestMatchers(HttpMethod.POST, "/stock/treatment-invoices/*/finalize").hasAnyRole(CLINICAL)
            .requestMatchers(HttpMethod.POST, "/stock/treatment-invoices/*/cancel").hasAnyRole(CLINICAL)
            .requestMatchers(HttpMethod.GET, "/stock/treatment-invoices/**").hasAnyRole(EVERYONE)
            .requestMatchers("/stock/treatment-invoices/**").hasAnyRole(EVERYONE)

            // ── Treatment catalogue: prices and consumable recipes ───────────
            .requestMatchers(HttpMethod.GET, "/stock/treatments/**").hasAnyRole(EVERYONE)
            .requestMatchers("/stock/treatments/**").hasAnyRole(CLINICAL)

            // ── Inventory and procurement ───────────────────────────────────
            // Deleting a purchase order, delivery note, vendor invoice, stock
            // item or supplier destroys an audit trail — owner only. Creating
            // and receiving them is exactly the front desk's job.
            .requestMatchers(HttpMethod.DELETE, "/stock/**").hasRole(ADMIN)
            .requestMatchers(HttpMethod.POST, "/stock/**").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.PUT, "/stock/**").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.GET, "/stock/**").hasAnyRole(EVERYONE)

            // ── Fail closed ─────────────────────────────────────────────────
            // Anything not named above is unreachable, including endpoints
            // added after this file was last read.
            .anyRequest().denyAll();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of("X-Correlation-Id"));
        // The API authenticates with a Bearer header, never a cookie, so
        // credentialed CORS is not needed — and leaving it on is what forces
        // the exact-origin echo and blocks a future wildcard. If auth ever
        // moves to a cookie (see the audit's H5), turn this back on together
        // with CSRF protection.
        configuration.setAllowCredentials(false);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
