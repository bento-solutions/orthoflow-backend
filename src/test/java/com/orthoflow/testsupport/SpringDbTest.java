package com.orthoflow.testsupport;

import com.orthoflow.OrthoflowApplication;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

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

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestSupport.dataSource(); // clean and migrate once, before Spring connects
        registry.add("spring.datasource.url", () -> System.getenv("ORTHOFLOW_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("ORTHOFLOW_TEST_DB_USER", "postgres"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("ORTHOFLOW_TEST_DB_PASSWORD", "postgres"));
    }
}
