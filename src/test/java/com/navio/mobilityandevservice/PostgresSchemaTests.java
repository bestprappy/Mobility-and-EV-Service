package com.navio.mobilityandevservice;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every migration against a disposable PostgreSQL with PostGIS, then starts
 * JPA with {@code ddl-auto=validate} exactly as production does. The default test
 * configuration uses H2 with Flyway disabled, which proves nothing about the real
 * schema. Skipped unless NAVIO_TEST_DB_URL is set; see docs/agents/database-changes.md.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=${NAVIO_TEST_DB_URL}",
        "spring.datasource.username=${NAVIO_TEST_DB_USERNAME:tripplanner}",
        "spring.datasource.password=${NAVIO_TEST_DB_PASSWORD:tripplanner}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "spring.flyway.schemas=ev",
        "spring.flyway.default-schema=ev",
        "spring.flyway.create-schemas=true",
        "spring.jpa.hibernate.ddl-auto=validate"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "NAVIO_TEST_DB_URL", matches = ".+")
class PostgresSchemaTests {

    @Autowired
    Flyway flyway;

    @Test
    void migrationsApplyAndEntityMappingsMatchThePostgresSchema() {
        assertThat(flyway.info().pending()).isEmpty();
    }
}
