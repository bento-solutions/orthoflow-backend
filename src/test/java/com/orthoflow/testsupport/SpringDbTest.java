package com.orthoflow.testsupport;

import com.orthoflow.OrthoflowApplication;
import com.orthoflow.common.security.AuthenticatedUser;
import org.junit.jupiter.api.AfterEach;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

/**
 * The whole application, services and JPA included, against the real PostgreSQL the
 * other database tests use. It exists for what a plain-JDBC test cannot see: the order
 * Hibernate flushes in, row locks, transaction rollback, and wiring. Those are exactly
 * where the bugs of "it compiled and every unit test passed" live (a raw-JDBC insert
 * that runs before the entity it references has been flushed, say).
 *
 * <p>Opt-in on {@code ORTHOFLOW_TEST_DB_URL}, like {@link PostgresTestSupport}. The
 * schema is rebuilt from the migrations <em>before</em> the context starts, so the
 * application's own Flyway finds nothing to do. The scheduled jobs are pushed far
 * enough out that none fires during a test.
 */
@SpringBootTest(classes = OrthoflowApplication.class, properties = {
        "app.jwt.secret=test-only-secret-key-for-spring-db-tests-0123456789abcdef",
        "orthoflow.cors.allowed-origins=http://localhost:4200",
        "orthoflow.messaging.sender.interval-ms=3600000",
        "spring.main.banner-mode=off"
})
@EnabledIfEnvironmentVariable(named = "ORTHOFLOW_TEST_DB_URL", matches = ".+")
public abstract class SpringDbTest {

    /**
     * What follows runs as a signed-in member of this clinic, as a request would: the
     * tenant filter scopes every query to it (ADR 0007). Without it a test reads nothing.
     */
    protected static void signInTo(UUID practiceId) {
        signInAs(UUID.randomUUID(), practiceId);
    }

    /** As {@link #signInTo(UUID)}, as a user that exists, for work that records who did it. */
    protected static void signInAs(UUID userId, UUID practiceId) {
        AuthenticatedUser user = new AuthenticatedUser(userId, "test@example.com", "ADMIN", practiceId);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    @AfterEach
    protected void signOut() {
        SecurityContextHolder.clearContext();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestSupport.dataSource(); // clean and migrate once, before Spring connects
        registry.add("spring.datasource.url", () -> System.getenv("ORTHOFLOW_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("ORTHOFLOW_TEST_DB_USER", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("ORTHOFLOW_TEST_DB_PASSWORD", "postgres"));
    }
}
