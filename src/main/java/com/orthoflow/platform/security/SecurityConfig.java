package com.orthoflow.platform.security;

import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.infrastructure.security.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
    @Order(2)
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
            // Renewing a session needs a session: any signed-in user, whatever
            // their role. Not public — a token that is already refused (expired,
            // deactivated account, issued before a password reset) gets a 401.
            .requestMatchers(HttpMethod.POST, "/auth/refresh").hasAnyRole(EVERYONE)
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
            // A consultation holds the whole conversation and what was
            // extracted from it: the same floor as the clinical record.
            .requestMatchers("/consultations/**").hasAnyRole(CLINICAL)
            // Orthodontic photos and radiographs belong to the clinical record.
            .requestMatchers(HttpMethod.GET, "/patients/*/photo-series", "/photo-series/**")
                    .hasAuthority(Permission.CLINICAL_READ.name())
            .requestMatchers("/patients/*/photo-series", "/photo-series/**").hasAuthority(Permission.CLINICAL_WRITE.name())

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
            .requestMatchers(HttpMethod.DELETE, "/patients/*").hasAuthority(Permission.PATIENT_DELETE.name())
            .requestMatchers(HttpMethod.POST, "/patients/*/merge").hasAuthority(Permission.PATIENT_MERGE.name())
            .requestMatchers(HttpMethod.GET, "/patients/*/merge-preview").hasAuthority(Permission.PATIENT_MERGE.name())
            .requestMatchers(HttpMethod.POST, "/patients", "/patients/*/photo").hasAuthority(Permission.PATIENT_WRITE.name())
            .requestMatchers(HttpMethod.PUT, "/patients/*").hasAuthority(Permission.PATIENT_WRITE.name())
            .requestMatchers(HttpMethod.GET, "/patients", "/patients/*", "/patients/list", "/patients/kpis",
                    "/patients/duplicates", "/patients/*/photo").hasAuthority(Permission.PATIENT_READ.name())
            // The lists a patient form draws from; settings change them.
            .requestMatchers(HttpMethod.GET, "/reference/**").hasAnyRole(EVERYONE)
            .requestMatchers("/reference/**").hasAuthority(Permission.SETTINGS_MANAGE.name())

            // ── Scheduling ──────────────────────────────────────────────────
            // Wholly front-desk work, including cancellations. Reading the
            // agenda and changing it are separate permissions.
            .requestMatchers(HttpMethod.GET, "/appointments", "/appointments/**").hasAuthority(Permission.AGENDA_VIEW.name())
            .requestMatchers("/appointments", "/appointments/**").hasAuthority(Permission.AGENDA_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/front-desk").hasAuthority(Permission.AGENDA_VIEW.name())
            .requestMatchers("/front-desk/**").hasAuthority(Permission.WAITING_ROOM_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/scheduling/**").hasAuthority(Permission.AGENDA_VIEW.name())
            // Types, rooms and chairs are clinic configuration; absences, events
            // and the waiting list are the receptionist's own business.
            .requestMatchers("/scheduling/appointment-types/**", "/scheduling/waiting-rooms/**", "/scheduling/chairs/**")
                    .hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers("/scheduling/**").hasAuthority(Permission.AGENDA_MANAGE.name())

            // ── Billing ─────────────────────────────────────────────────────
            // Taking payment is front-desk work; what the clinic has taken in total,
            // the cash close and the cheque lifecycle are not.
            .requestMatchers(HttpMethod.POST, "/invoices/*/payments").hasAuthority(Permission.BILLING_WRITE.name())
            .requestMatchers(HttpMethod.POST, "/invoices").hasAuthority(Permission.BILLING_WRITE.name())
            // Whose invoice it is decides whose pay it counts toward.
            .requestMatchers(HttpMethod.PUT, "/invoices/*/practitioner").hasAuthority(Permission.FINANCE_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/invoices/summary").hasAuthority(Permission.FINANCE_VIEW.name())
            .requestMatchers(HttpMethod.GET, "/invoices", "/invoices/**").hasAuthority(Permission.BILLING_READ.name())
            .requestMatchers(HttpMethod.POST, "/patients/*/receipts", "/patients/*/credit/apply", "/patients/*/payment-plans")
                    .hasAuthority(Permission.BILLING_WRITE.name())
            .requestMatchers(HttpMethod.GET, "/patients/*/account", "/patients/*/payment-plans", "/payment-plans/*")
                    .hasAuthority(Permission.BILLING_READ.name())
            .requestMatchers(HttpMethod.POST, "/payment-plans/instalments/*/pay").hasAuthority(Permission.BILLING_WRITE.name())
            .requestMatchers(HttpMethod.GET, "/payment-plans/due").hasAuthority(Permission.BILLING_READ.name())
            .requestMatchers(HttpMethod.POST, "/receipts/*/void", "/payment-plans/*/cancel").hasAuthority(Permission.FINANCE_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/cheques").hasAuthority(Permission.BILLING_READ.name())
            .requestMatchers("/cheques", "/cheques/**").hasAuthority(Permission.FINANCE_MANAGE.name())

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

            // ── Recall lists: who to call (queries over what the clinic knows) ──
            .requestMatchers(HttpMethod.POST, "/recalls/send-reminders").hasAuthority(Permission.MESSAGING_SEND.name())
            .requestMatchers(HttpMethod.GET, "/recalls/**").hasAuthority(Permission.AGENDA_VIEW.name())
            .requestMatchers(HttpMethod.GET, "/settings/messaging").hasAuthority(Permission.MESSAGING_VIEW.name())
            .requestMatchers(HttpMethod.PUT, "/settings/messaging").hasAuthority(Permission.SETTINGS_MANAGE.name())

            // ── Tasks and internal messages ─────────────────────────────────
            .requestMatchers(HttpMethod.GET, "/tasks").hasAuthority(Permission.TASKS_ADMIN.name())
            .requestMatchers("/tasks", "/tasks/**").hasAuthority(Permission.TASKS_MANAGE.name())
            // A thread is visible to its participants only, which the service checks.
            .requestMatchers("/staff-messages", "/staff-messages/**").hasAnyRole(EVERYONE)

            // ── Online booking: staff side. The public pages are on their own chain. ──
            .requestMatchers(HttpMethod.PUT, "/booking/settings").hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers("/booking", "/booking/**").hasAuthority(Permission.BOOKING_REVIEW.name())

            // ── Self-registration review and satisfaction surveys (the public forms are on their own chain) ──
            .requestMatchers(HttpMethod.POST, "/patients/*/registration-invite").hasAuthority(Permission.PATIENT_WRITE.name())
            .requestMatchers("/registrations", "/registrations/**").hasAuthority(Permission.BOOKING_REVIEW.name())
            .requestMatchers("/surveys", "/surveys/**").hasAuthority(Permission.SURVEYS_VIEW.name())

            // ── Lab orders ───────────────────────────────────────────────────
            .requestMatchers("/lab-orders", "/lab-orders/**").hasAuthority(Permission.LAB_ORDERS_MANAGE.name())

            // ── Tax documents: fee notes and care forms ───────────────────────
            .requestMatchers(HttpMethod.GET, "/tax-documents", "/tax-documents/**").hasAuthority(Permission.BILLING_READ.name())
            .requestMatchers(HttpMethod.POST, "/tax-documents").hasAuthority(Permission.BILLING_WRITE.name())
            .requestMatchers("/tax-documents/**").hasAuthority(Permission.FINANCE_MANAGE.name())

            // ── The patient's insurer's care form: filled by the session, printed by the front desk ──
            .requestMatchers(HttpMethod.GET, "/insurance-forms", "/insurance-forms/**").hasAuthority(Permission.BILLING_READ.name())
            .requestMatchers("/insurance-forms", "/insurance-forms/**").hasAuthority(Permission.BILLING_WRITE.name())

            // ── Prescriptions: clinical, written and read by the clinical team ──
            .requestMatchers(HttpMethod.GET, "/prescriptions", "/prescriptions/**").hasAuthority(Permission.CLINICAL_READ.name())
            .requestMatchers("/prescriptions", "/prescriptions/**").hasAuthority(Permission.CLINICAL_WRITE.name())

            // ── Finance: what the clinic has taken in and spent ─────────────────
            // Totals are not front-desk data (an assistant can take a payment but
            // not read the clinic's takings); closing the cash and keeping the books
            // are finance management.
            .requestMatchers(HttpMethod.GET, "/finance/debts", "/finance/debts/export").hasAuthority(Permission.BILLING_READ.name())
            .requestMatchers(HttpMethod.POST, "/finance/cash-closing").hasAuthority(Permission.FINANCE_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/finance/collections", "/finance/dashboard", "/finance/dashboard/export",
                    "/finance/cash-closing/*").hasAuthority(Permission.FINANCE_VIEW.name())
            // ── Help centre ─────────────────────────────────────────────────
            // Everyone signed in reads the notes and may ask the assistant (which
            // is off unless the clinic switches it on); only settings managers
            // edit the clinic's own wording.
            .requestMatchers(HttpMethod.PUT, "/help/notes/*").hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers(HttpMethod.DELETE, "/help/notes/*").hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/help/notes", "/help/notes/*").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.POST, "/help/ask").hasAnyRole(EVERYONE)

            // ── Sterilization, endo kits, handpieces ────────────────────────
            // Reading the register is as sensitive as writing it (it names the
            // patients an instrument touched), so one permission covers both.
            .requestMatchers("/sterilization/**", "/endo/**").hasAuthority(Permission.STERILIZATION_MANAGE.name())

            // ── Practice analytics ──────────────────────────────────────────
            // Activity and doctor time are ANALYTICS_VIEW. The income statement
            // and goals show the clinic's money (and the owner's personal needs),
            // so they follow the finance permissions.
            .requestMatchers(HttpMethod.GET, "/analytics/procedures", "/analytics/procedures/export", "/analytics/doctor-time")
                    .hasAuthority(Permission.ANALYTICS_VIEW.name())
            .requestMatchers(HttpMethod.GET, "/analytics/income-statement", "/analytics/income-statement/export",
                    "/analytics/goals/*", "/analytics/tax-schedule/*").hasAuthority(Permission.FINANCE_VIEW.name())
            .requestMatchers(HttpMethod.POST, "/analytics/goals/plan", "/analytics/tax-simulation")
                    .hasAuthority(Permission.FINANCE_VIEW.name())
            .requestMatchers(HttpMethod.PUT, "/analytics/goals/*", "/analytics/tax-schedule/*")
                    .hasAuthority(Permission.FINANCE_MANAGE.name())

            // ── Retrocessions: what collaborators are paid ──────────────────
            // Reading is VIEW (a viewer without MANAGE sees only their own
            // figures, enforced per row in RetrocessionAccess); anything that
            // changes terms, records money or validates a statement is MANAGE.
            .requestMatchers(HttpMethod.GET, "/retrocessions/**").hasAuthority(Permission.RETROCESSION_VIEW.name())
            .requestMatchers("/retrocessions/**").hasAuthority(Permission.RETROCESSION_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/finance/expenses", "/finance/expenses/**").hasAuthority(Permission.FINANCE_VIEW.name())
            .requestMatchers("/finance/expenses", "/finance/expenses/**").hasAuthority(Permission.EXPENSES_MANAGE.name())

            // ── Account, team and platform (Denteam parity, phase 0) ────────────
            // Permissions are the finer gate behind the role floor: a role holds
            // them by default and an admin can change that (ADR 0008).
            .requestMatchers("/me", "/me/**").hasAnyRole(EVERYONE)
            .requestMatchers("/events").hasAnyRole(EVERYONE)
            .requestMatchers("/notifications", "/notifications/**").hasAnyRole(EVERYONE)
            // Each file is checked against the permission of the record it belongs to.
            .requestMatchers("/files", "/files/**").hasAnyRole(EVERYONE)
            .requestMatchers(HttpMethod.GET, "/activity").hasAnyRole(EVERYONE)
            .requestMatchers("/admin/**").hasAuthority(Permission.USERS_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/practitioners", "/practitioners/*").hasAnyRole(EVERYONE)
            .requestMatchers("/practitioners/**").hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers(HttpMethod.GET, "/settings/practice/profile", "/settings/practice/logo",
                    "/settings/practice/opening-hours", "/settings/practice/status-colors").hasAnyRole(EVERYONE)
            .requestMatchers("/settings/practice/profile", "/settings/practice/logo",
                    "/settings/practice/opening-hours", "/settings/practice/status-colors")
                    .hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers("/public-links/**").hasAuthority(Permission.SETTINGS_MANAGE.name())

            // ── Messaging ───────────────────────────────────────────────────
            .requestMatchers(HttpMethod.PUT, "/messaging/templates").hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers(HttpMethod.DELETE, "/messaging/templates/**").hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers("/messaging/send-test").hasAuthority(Permission.SETTINGS_MANAGE.name())
            .requestMatchers(HttpMethod.POST, "/messaging/logs/**", "/messaging/inbox/**")
                    .hasAuthority(Permission.MESSAGING_SEND.name())
            .requestMatchers(HttpMethod.GET, "/messaging/**").hasAuthority(Permission.MESSAGING_VIEW.name())
            .requestMatchers(HttpMethod.POST, "/messaging/templates/preview").hasAuthority(Permission.MESSAGING_VIEW.name())
            .requestMatchers(HttpMethod.GET, "/patients/*/consent").hasAuthority(Permission.PATIENT_READ.name())
            .requestMatchers(HttpMethod.PUT, "/patients/*/consent").hasAuthority(Permission.PATIENT_WRITE.name())

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
        // Content-Disposition carries the filename of an export (pdf, xlsx, csv). Without exposing it the
        // browser, calling from another origin, cannot read it and every download loses its name.
        configuration.setExposedHeaders(List.of("X-Correlation-Id", "Content-Disposition"));
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
