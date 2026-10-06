package com.orthoflow.platform.security;

import com.orthoflow.auth.application.port.AuthorityResolver;
import com.orthoflow.auth.application.port.SessionRegistry;
import com.orthoflow.auth.domain.model.Permission;
import com.orthoflow.auth.domain.model.User;
import com.orthoflow.auth.domain.model.UserRole;
import com.orthoflow.auth.domain.repository.UserRepository;
import com.orthoflow.auth.infrastructure.security.JwtAuthFilter;
import com.orthoflow.auth.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.test.context.web.WebAppConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Asserts the authorization matrix declared in {@link SecurityConfig#policy}.
 *
 * <p>No real controllers are loaded — {@link Noop} is a controller with zero
 * request mappings, imported in place of the application's 22. Spring
 * Security's filter chain runs ahead of the {@code DispatcherServlet}, so what
 * happens to a request depends only on {@link SecurityConfig}: one the policy
 * denies is stopped before dispatch (401 or 403); one it allows reaches
 * dispatch and, since nothing is mapped, comes back 404. That distinction —
 * blocked vs. reached-the-absent-handler — is what every case below checks,
 * which is what lets this test exercise the real policy without a database,
 * JPA, or any business service.
 *
 * <p>Before this class, the application had no authorization test at all:
 * fifteen of twenty-two controllers had no {@code @PreAuthorize} and were
 * reachable by any authenticated session regardless of role (see the
 * architecture review this file's introduction refers to). Every branch of
 * {@code SecurityConfig#policy} has at least one case here, so a rule
 * loosened, deleted, or simply forgotten on a new endpoint shows a failing
 * assertion instead of shipping silently.
 *
 * <p>This is deliberately not {@code @WebMvcTest}: that annotation walks up
 * from this test's package looking for the nearest {@code @SpringBootConfiguration}
 * and finds the real {@code OrthoflowApplication} — whose explicit
 * {@code @EnableJpaRepositories}/{@code @EntityScan} then try to wire a real
 * {@code EntityManagerFactory} even inside the slice, which fails with no
 * datasource. Listing exactly the classes this test actually needs via
 * {@code @ContextConfiguration} sidesteps that discovery entirely.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = {
        SecurityConfig.class, JwtAuthFilter.class, JwtService.class, AuthRateLimitFilter.class,
        RestAuthenticationEntryPoint.class, RestAccessDeniedHandler.class,
        SecurityPolicyTest.Noop.class, SecurityPolicyTest.MvcConfig.class,
        SecurityPolicyTest.StubUsers.class
})
@WebAppConfiguration
@TestPropertySource(properties = {
        "app.jwt.secret=test-only-secret-key-for-security-policy-tests-0123456789",
        "app.jwt.expiration-minutes=60",
        "orthoflow.cors.allowed-origins=http://localhost:4200"
})
class SecurityPolicyTest {

    /**
     * Deliberately empty: no {@code @RequestMapping} methods, so any request
     * that clears {@link SecurityConfig}'s filter chain has nowhere to land
     * and comes back 404 — the "security let this through" signal these tests
     * read for the allowed side of every case.
     */
    @RestController
    static class Noop {
    }

    /**
     * Spring Security's {@code requestMatchers(...)} resolve paths through
     * {@code MvcRequestMatcher}, which needs an {@code HandlerMappingIntrospector}
     * bean — supplied by {@code @EnableWebMvc}. Production gets this for free
     * from Spring Boot's web autoconfiguration; this slice has to ask for it
     * explicitly since nothing else here pulls in Spring MVC.
     */
    @Configuration
    @EnableWebMvc
    static class MvcConfig {
    }

    /**
     * {@link JwtAuthFilter} now loads the user on every request (to check
     * {@code active} and the password-reset cutoff), so it needs a
     * {@link UserRepository}. This slice has no database; the stub maps the
     * three deterministic per-role ids {@link #idFor} mints onto an active
     * user with that role, and knows no other id.
     */
    @Configuration
    static class StubUsers {
        /** Permissions as a fresh install has them: the code-defined defaults. */
        @org.springframework.context.annotation.Bean
        AuthorityResolver authorityResolver() {
            return (practiceId, role) -> Permission.defaultsFor(role);
        }

        @org.springframework.context.annotation.Bean
        SessionRegistry sessionRegistry() {
            return sessionId -> true;
        }

        @org.springframework.context.annotation.Bean
        UserRepository userRepository() {
            Map<UUID, UserRole> byId = Map.of(
                    idFor(ASSISTANT), UserRole.ASSISTANT,
                    idFor(DOCTOR), UserRole.DOCTOR,
                    idFor(ADMIN), UserRole.ADMIN);
            return new UserRepository() {
                @Override
                public Optional<User> findById(UUID id) {
                    return Optional.ofNullable(byId.get(id)).map(role -> User.builder()
                            .id(id).email("test@example.com").role(role).active(true)
                            .passwordHash("x").firstName("T").lastName("U").build());
                }
                @Override public User save(User user) { throw new UnsupportedOperationException(); }
                @Override public Optional<User> findByEmail(String email) { return Optional.empty(); }
                @Override public List<User> findAll() { return List.of(); }
                @Override public boolean existsAny() { return true; }
            };
        }
    }

    private static final String ASSISTANT = "ASSISTANT";
    private static final String DOCTOR = "DOCTOR";
    private static final String ADMIN = "ADMIN";
    private static final Set<String> ALL_ROLES = Set.of(ASSISTANT, DOCTOR, ADMIN);

    private static final String ID = "11111111-1111-1111-1111-111111111111";

    /** A stable, per-role user id so {@link StubUsers} can resolve it back to a role. */
    private static UUID idFor(String role) {
        return switch (role) {
            case ASSISTANT -> UUID.fromString("00000000-0000-0000-0000-0000000000a5");
            case DOCTOR -> UUID.fromString("00000000-0000-0000-0000-0000000000d0");
            case ADMIN -> UUID.fromString("00000000-0000-0000-0000-0000000000ad");
            default -> throw new IllegalArgumentException(role);
        };
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JwtService jwtService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    private record Case(HttpMethod method, String path, boolean anonymousAllowed, Set<String> allowedRoles,
                         String label) {

        /** A route that requires authentication and, among authenticated users, one of {@code allowedRoles}. */
        static Case restricted(HttpMethod method, String path, String label, String... allowedRoles) {
            return new Case(method, path, false, Set.of(allowedRoles), label);
        }

        /** A route open to anonymous callers and therefore to every role too. */
        static Case pub(HttpMethod method, String path, String label) {
            return new Case(method, path, true, ALL_ROLES, label);
        }

        @Override
        public String toString() {
            return method + " " + path + " — " + label;
        }
    }

    static Stream<Case> cases() {
        return Stream.of(
                // ── Public ────────────────────────────────────────────────
                Case.pub(HttpMethod.POST, "/auth/login", "login is public"),
                Case.restricted(HttpMethod.POST, "/auth/refresh",
                        "renewing a session needs a session, from any role", ASSISTANT, DOCTOR, ADMIN),
                Case.pub(HttpMethod.GET, "/actuator/health", "actuator is reachable inside the Docker network only, not gated further here"),
                Case.pub(HttpMethod.GET, "/v3/api-docs", "API docs are public and disabled outright in prod"),

                // ── Clinical record: clinicians only ─────────────────────────
                Case.restricted(HttpMethod.GET, "/patients/" + ID + "/clinical-record",
                        "the clinical record is clinician-only", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/patients/" + ID + "/clinical-record/notes",
                        "adding a clinical note is clinician-only", DOCTOR, ADMIN),

                // ── Dental chart: everyone reads, clinicians write ───────────
                Case.restricted(HttpMethod.GET, "/patients/" + ID + "/dental-chart",
                        "reading the chart is open to the front desk", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.PUT, "/patients/" + ID + "/dental-chart/teeth/11",
                        "changing a tooth is clinician-only", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/clinical/finding-catalog",
                        "the finding catalog is open to the front desk", ASSISTANT, DOCTOR, ADMIN),

                // ── Voice: dictates into the clinical record ─────────────────
                Case.restricted(HttpMethod.POST, "/voice/sessions",
                        "voice sessions inherit the clinical record's floor", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/voice/transcribe",
                        "capture-to-text carries consultation audio and is clinician-only", DOCTOR, ADMIN),

                // ── Consultations: the whole conversation, kept ──────────────
                Case.restricted(HttpMethod.POST, "/consultations",
                        "recording a consultation is a clinical act", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/consultations/" + ID,
                        "a transcript is the most sensitive text in the system", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/consultations/" + ID + "/extract",
                        "reading a conversation sends it to a model", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/consultations/" + ID + "/commit",
                        "saving a consultation writes the clinical record", DOCTOR, ADMIN),

                // ── Compliance: the operator's duty ──────────────────────────
                Case.restricted(HttpMethod.GET, "/patients/" + ID + "/compliance/export",
                        "the data export is owner-only", ADMIN),

                // ── Treatment sessions (patient-scoped, served by stock) ─────
                Case.restricted(HttpMethod.GET, "/patients/treatments",
                        "the treatment list is open to the front desk", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/patients/" + ID + "/treatments",
                        "recording a session is a clinical act", DOCTOR, ADMIN),

                // ── Patients ──────────────────────────────────────────────────
                Case.restricted(HttpMethod.DELETE, "/patients/" + ID + "/erase",
                        "erasure is owner-only", ADMIN),
                Case.restricted(HttpMethod.DELETE, "/patients/" + ID,
                        "deleting a patient is owner-only", ADMIN),
                Case.restricted(HttpMethod.POST, "/patients",
                        "registering a patient is open to the front desk", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/patients",
                        "listing patients is open to the front desk", ASSISTANT, DOCTOR, ADMIN),

                // ── Scheduling ────────────────────────────────────────────────
                Case.restricted(HttpMethod.POST, "/appointments",
                        "booking is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.DELETE, "/appointments/" + ID,
                        "cancelling is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/scheduling/chairs",
                        "the chair list is front-desk work", ASSISTANT, DOCTOR, ADMIN),

                // ── Billing ───────────────────────────────────────────────────
                Case.restricted(HttpMethod.POST, "/invoices",
                        "invoicing is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/invoices/" + ID + "/payments",
                        "recording payment is front-desk work", ASSISTANT, DOCTOR, ADMIN),

                // ── Practice settings ────────────────────────────────────────
                Case.restricted(HttpMethod.GET, "/settings/practice",
                        "reading settings is open to everyone", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.PUT, "/settings/practice",
                        "changing settings is owner-only", ADMIN),

                // ── Reporting: margins are not front-desk data ───────────────
                Case.restricted(HttpMethod.GET, "/stock/analytics/kpi",
                        "inventory KPIs are clinician-only", DOCTOR, ADMIN),

                // ── Treatment invoices (cost/consumption records) ────────────
                Case.restricted(HttpMethod.DELETE, "/stock/treatment-invoices/" + ID,
                        "deleting a cost record is owner-only", ADMIN),
                Case.restricted(HttpMethod.POST, "/stock/treatment-invoices/" + ID + "/finalize",
                        "finalizing creates the patient-facing invoice (ADR-0005) and is clinical", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/stock/treatment-invoices",
                        "listing cost records is front-desk work", ASSISTANT, DOCTOR, ADMIN),

                // ── Treatment catalogue: prices and consumable recipes ───────
                Case.restricted(HttpMethod.GET, "/stock/treatments",
                        "the price list is open to the front desk", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/stock/treatments",
                        "editing the price list is clinician-only", DOCTOR, ADMIN),

                // ── Account, team, platform (phase 0) ────────────────────────
                Case.restricted(HttpMethod.GET, "/me", "everyone may read their own account", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/me/change-password", "everyone may change their own password", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/events", "the live stream is for signed-in staff", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/notifications", "the bell is for signed-in staff", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/files/" + ID, "file access is further checked per owner type", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/admin/users", "user management is admin-only by default", ADMIN),
                Case.restricted(HttpMethod.PUT, "/admin/permissions/DOCTOR", "editing permissions is admin-only", ADMIN),
                Case.restricted(HttpMethod.GET, "/practitioners", "the agenda needs the doctors, so everyone reads", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/practitioners", "creating a practitioner is a settings change", ADMIN),
                Case.restricted(HttpMethod.GET, "/settings/practice/profile", "the letterhead is readable by all", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.PUT, "/settings/practice/profile", "changing the letterhead is a settings change", ADMIN),
                Case.restricted(HttpMethod.PUT, "/settings/practice/opening-hours", "opening hours are a settings change", ADMIN),
                Case.restricted(HttpMethod.GET, "/messaging/logs", "the message log is for staff who may message", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.PUT, "/messaging/templates", "templates are a settings change", ADMIN),
                Case.restricted(HttpMethod.POST, "/messaging/send-test", "a test message is a settings action", ADMIN),
                Case.restricted(HttpMethod.PUT, "/patients/" + ID + "/consent", "recording consent is patient maintenance", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/public-links/shared/BOOKING/rotate", "rotating the booking link is a settings change", ADMIN),

                // ── Front desk (phase 1) ──────────────────────────────────────
                Case.restricted(HttpMethod.GET, "/front-desk", "the waiting-room board is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/front-desk/" + ID + "/seat", "calling a patient in is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/scheduling/appointment-types", "appointment types are clinic configuration", ADMIN),
                Case.restricted(HttpMethod.POST, "/scheduling/absences", "recording an absence is the receptionist's business", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/appointments/daily-sheet", "the day list is open to the front desk", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/patients/list", "the patient list is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/patients/duplicates", "duplicate detection is open to those who read patients", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/patients/" + ID + "/merge", "merging patients is irreversible and owner-only by default", ADMIN),
                Case.restricted(HttpMethod.GET, "/recalls/NO_VISIT_3M", "recall lists are open to the front desk", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.PUT, "/reference/insurers/" + ID, "editing insurers is a settings change", ADMIN),
                Case.restricted(HttpMethod.GET, "/reference/insurers", "the insurer list feeds the patient form", ASSISTANT, DOCTOR, ADMIN),

                // ── Money (phase 2): an assistant takes payment but does not read the takings ──
                Case.restricted(HttpMethod.POST, "/patients/" + ID + "/receipts", "taking money is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/patients/" + ID + "/account", "a patient's own account is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/receipts/" + ID + "/void", "reversing a receipt is finance management", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/invoices/summary", "the clinic's totals are not front-desk data", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/finance/dashboard", "the financial dashboard shows the takings", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/finance/collections", "collections show the takings", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/finance/debts", "the debt list is what the front desk chases", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/finance/cash-closing", "closing the cash is finance management", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/finance/expenses", "expenses are financial data", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/finance/expenses", "recording expenses is owner-level by default", ADMIN),
                Case.restricted(HttpMethod.GET, "/cheques", "the cheque register can be read by the front desk", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/cheques/" + ID + "/reject", "a bounced cheque reverses money: finance management", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/payment-plans/instalments/" + ID + "/pay", "collecting an instalment is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/payment-plans/" + ID + "/cancel", "cancelling a plan is finance management", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/tax-documents", "issuing a fee note is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/tax-documents/" + ID + "/void", "voiding a tax document is finance management", DOCTOR, ADMIN),

                // ── Clinic operations (phase 3) ───────────────────────────────
                Case.restricted(HttpMethod.GET, "/lab-orders", "lab orders are managed by whoever holds LAB_ORDERS_MANAGE", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/lab-orders/" + ID + "/status", "moving a lab order along", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/tasks/mine", "everyone has tasks of their own", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/tasks", "everyone's tasks is the administrator's view", ADMIN),
                Case.restricted(HttpMethod.POST, "/tasks", "creating a task is open to staff", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/staff-messages/threads", "internal messages are for staff", ASSISTANT, DOCTOR, ADMIN),

                // ── Practice analytics and team pay (phase 5) ─────────────────
                Case.restricted(HttpMethod.GET, "/retrocessions/simulation", "what collaborators are owed: viewers see only their own, enforced per row", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/retrocessions/rules", "pay terms are for those who may view retrocessions", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/retrocessions/rules", "setting what a collaborator is paid is owner-level by default", ADMIN),
                Case.restricted(HttpMethod.DELETE, "/retrocessions/rules/" + ID, "deleting pay terms is owner-level by default", ADMIN),
                Case.restricted(HttpMethod.POST, "/retrocessions/advances", "handing out an advance is owner-level by default", ADMIN),
                Case.restricted(HttpMethod.DELETE, "/retrocessions/advances/" + ID, "deleting an advance is owner-level by default", ADMIN),
                Case.restricted(HttpMethod.POST, "/retrocessions/statements", "validating a statement freezes what a person is paid", ADMIN),
                Case.restricted(HttpMethod.POST, "/retrocessions/statements/" + ID + "/void", "voiding a validated statement", ADMIN),
                Case.restricted(HttpMethod.POST, "/retrocessions/statements/" + ID + "/payouts", "recording money paid to a collaborator", ADMIN),
                Case.restricted(HttpMethod.GET, "/retrocessions/statements/" + ID + "/pdf", "a statement is readable by its own practitioner and the owner", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.PUT, "/invoices/" + ID + "/practitioner", "whose invoice it is decides whose pay it counts toward", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/analytics/procedures", "procedure margins are not front-desk data", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/analytics/procedures/export", "exporting procedure margins", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/analytics/doctor-time", "doctor time is a management view", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/analytics/income-statement", "the income statement shows the practice's money", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/analytics/income-statement/export", "exporting the income statement", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/analytics/goals/2026", "goals include the owner's personal needs", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/analytics/goals/plan", "the goal wizard calculation", DOCTOR, ADMIN),
                Case.restricted(HttpMethod.PUT, "/analytics/goals/2026", "saving a goal is finance management", DOCTOR, ADMIN),

                // ── Sterilization, endo and help (phase 6) ────────────────────
                Case.restricted(HttpMethod.GET, "/sterilization/items", "the register names the patients an instrument touched", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/sterilization/items/" + ID + "/use", "using an instrument records the patient", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/sterilization/cycles", "loading an autoclave", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/sterilization/cycles/" + ID + "/control", "releasing or recalling a load", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/sterilization/traceability/patients/" + ID, "which instruments touched a patient", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/sterilization/labels?itemId=" + ID, "printing QR labels", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/endo/kits", "endo kits and their file counts", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/endo/files/" + ID + "/discard", "discarding a worn file", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.GET, "/help/notes", "everyone signed in reads the help", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/help/ask", "everyone signed in may ask the assistant", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.PUT, "/help/notes/agenda?lang=fr", "editing the clinic's own wording is a settings task", ADMIN),
                Case.restricted(HttpMethod.DELETE, "/help/notes/agenda?lang=fr", "restoring the built-in wording is a settings task", ADMIN),

                // ── Inventory and procurement ────────────────────────────────
                Case.restricted(HttpMethod.GET, "/stock/items",
                        "browsing inventory is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.POST, "/stock/items",
                        "receiving stock is front-desk work", ASSISTANT, DOCTOR, ADMIN),
                Case.restricted(HttpMethod.DELETE, "/stock/items/" + ID,
                        "deleting a stock item destroys an audit trail — owner-only", ADMIN),
                Case.restricted(HttpMethod.DELETE, "/stock/purchase-orders/" + ID,
                        "deleting a purchase order — the exact case the old policy left open to any authenticated session",
                        ADMIN)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void enforcesRolesFor(Case testCase) throws Exception {
        for (String role : ALL_ROLES) {
            ResultActions result = mockMvc.perform(request(testCase.method(), testCase.path())
                    .header("Authorization", "Bearer " + tokenFor(role)));
            if (testCase.allowedRoles().contains(role)) {
                result.andExpect(status().isNotFound());
            } else {
                assertDenied(result, role + " on " + testCase);
            }
        }

        ResultActions anonymous = mockMvc.perform(request(testCase.method(), testCase.path()));
        if (testCase.anonymousAllowed()) {
            anonymous.andExpect(status().isNotFound());
        } else {
            assertDenied(anonymous, "anonymous on " + testCase);
        }
    }

    /**
     * The single most important assertion in this file: an endpoint this
     * matrix has never heard of is unreachable to every role, including
     * ADMIN. This is what {@code anyRequest().denyAll()} buys over the
     * {@code anyRequest().authenticated()} it replaced — a forgotten new
     * endpoint fails closed instead of opening to any authenticated session.
     */
    @Test
    void anUnlistedEndpointIsUnreachableToEveryRole() throws Exception {
        String path = "/some/endpoint/nobody/declared";
        for (String role : ALL_ROLES) {
            assertDenied(
                    mockMvc.perform(request(HttpMethod.GET, path).header("Authorization", "Bearer " + tokenFor(role))),
                    role + " on an unlisted endpoint");
        }
        assertDenied(mockMvc.perform(request(HttpMethod.GET, path)), "anonymous on an unlisted endpoint");
    }

    private String tokenFor(String role) {
        return jwtService.generateToken(idFor(role), "test@example.com", role);
    }

    /**
     * Denied means the filter chain stopped the request before dispatch — 401
     * (no or invalid credentials) or 403 (authenticated, wrong role). Which of
     * the two Spring Security picks here depends on entry-point wiring this
     * slice doesn't configure, so both are accepted; a 404 would mean the
     * request wrongly reached the (absent) handler, i.e. security let it pass.
     */
    private void assertDenied(ResultActions result, String description) throws Exception {
        int status = result.andReturn().getResponse().getStatus();
        assertThat(status)
                .as("expected %s to be blocked by SecurityConfig (401 or 403), got %d", description, status)
                .isIn(401, 403);
    }
}
