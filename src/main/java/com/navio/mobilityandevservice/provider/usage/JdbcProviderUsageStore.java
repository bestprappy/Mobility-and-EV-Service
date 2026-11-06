package com.navio.mobilityandevservice.provider.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;

@Repository
public class JdbcProviderUsageStore implements ProviderUsageStore {

    // One statement, so concurrent instances can never count past the limit.
    private static final String CONSUME = """
            INSERT INTO ev.provider_monthly_usage (usage_month, meter, request_count, updated_at)
            VALUES (?, ?, 1, now())
            ON CONFLICT (usage_month, meter) DO UPDATE
               SET request_count = ev.provider_monthly_usage.request_count + 1,
                   updated_at = now()
             WHERE ev.provider_monthly_usage.request_count < ?
            RETURNING request_count
            """;

    private final JdbcTemplate jdbcTemplate;

    public JdbcProviderUsageStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public boolean tryConsume(LocalDate usageMonth, String meter, int limit) {
        if (limit <= 0) {
            return false;
        }
        return !jdbcTemplate.queryForList(CONSUME, Integer.class, usageMonth, meter, limit).isEmpty();
    }
}
