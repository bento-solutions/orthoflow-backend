package com.orthoflow.testsupport;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.util.UUID;

/**
 * A real PostgreSQL for the tests that exist because the logic is SQL: the
 * patient list, duplicate detection, recall lists, the exclusion constraints.
 * Mocks cannot say whether a query is right.
 *
 * <p>Opt-in: set {@code ORTHOFLOW_TEST_DB_URL} (for example
 * {@code jdbc.postgresql://localhost:5432/orthoflow_test}, a database that may be
 * wiped), plus {@code ORTHOFLOW_TEST_DB_USER} / {@code ORTHOFLOW_TEST_DB_PASSWORD}.
 * The schema is dropped and rebuilt from the real migrations once per JVM, and
 * each test works inside a practice of its own so tests cannot see each other.
 * Without the variable the tests are skipped, so a laptop without Postgres
 * still builds; CI sets it.
 */
public final class PostgresTestSupport {

    private static DataSource migrated;

    private PostgresTestSupport() {
    }

    public static synchronized DataSource dataSource() {
        if (migrated == null) {
            String url = System.getenv("ORTHOFLOW_TEST_DB_URL");
            String user = System.getenv().getOrDefault("ORTHOFLOW_TEST_DB_USER", "postgres");
            String password = System.getenv().getOrDefault("ORTHOFLOW_TEST_DB_PASSWORD", "postgres");
            DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
            Flyway flyway = Flyway.configure().dataSource(ds).locations("classpath:db/migration").cleanDisabled(false).load();
            flyway.clean();
            flyway.migrate();
            migrated = ds;
        }
        return migrated;
    }

    public static JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource());
    }

    /** A clinic of its own: every row a test creates belongs to it. */
    public static UUID newPractice(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO practices (id, name) VALUES (?, ?)", id, "Test clinic " + id);
        return id;
    }

    public static UUID patient(JdbcTemplate jdbc, UUID practice, String first, String last, String dob, String phone, String cin) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO patients (id, practice_id, first_name, last_name, date_of_birth, phone, cin, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, CAST(? AS date), ?, ?, 'ACTIVE', now(), now())
                """, id, practice, first, last, dob, phone, cin);
        return id;
    }
}
