package com.navio.mobilityandevservice;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import com.navio.mobilityandevservice.provider.usage.JdbcProviderUsageStore;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs every migration against a disposable PostgreSQL with PostGIS, then starts
 * JPA with {@code ddl-auto=validate} exactly as production does. The default test
 * configuration uses H2 with Flyway disabled, which proves nothing about the real
 * schema. Skipped unless NAVIO_TEST_DB_URL is set; see docs/agents/database-changes.md.
 *
 * <p>The Google free-tier counter's upsert is PostgreSQL-only and fails closed (every Google
 * call refused) if it breaks, so it is exercised here where the release pipeline runs it.
 * Each test rolls back, so a reused disposable database stays clean.
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
@Import(JdbcProviderUsageStore.class)
class PostgresSchemaTests {

    private static final LocalDate MONTH = LocalDate.of(1999, 1, 1);

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcProviderUsageStore usageStore;

    @Test
    void migrationsApplyAndEntityMappingsMatchThePostgresSchema() {
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void freeTierCounterCountsUpToTheLimitThenRefuses() {
        assertThat(usageStore.tryConsume(MONTH, "PLACE_PHOTOS", 2)).isTrue();
        assertThat(usageStore.tryConsume(MONTH, "PLACE_PHOTOS", 2)).isTrue();
        assertThat(usageStore.tryConsume(MONTH, "PLACE_PHOTOS", 2)).isFalse();
        assertThat(usageStore.tryConsume(MONTH, "PLACE_PHOTOS", 2)).isFalse();
    }

    @Test
    void freeTierCounterKeepsEachMonthAndMeterSeparate() {
        assertThat(usageStore.tryConsume(MONTH, "ROUTES", 1)).isTrue();
        assertThat(usageStore.tryConsume(MONTH, "ROUTES", 1)).isFalse();
        assertThat(usageStore.tryConsume(MONTH, "PLACE_DETAILS", 1)).isTrue();
        assertThat(usageStore.tryConsume(MONTH.plusMonths(1), "ROUTES", 1)).isTrue();
    }
}
