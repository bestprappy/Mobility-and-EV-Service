package com.navio.mobilityandevservice.provider.usage;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcProviderUsageStore implements ProviderUsageStore {

    // One statement, so concurrent instances can never count past the limit. The override
    // check sits in the same statement, so turning limits back on takes effect on the next call.
    private static final String CONSUME = """
            INSERT INTO ev.provider_monthly_usage (usage_month, meter, request_count, updated_at)
            VALUES (?, ?, 1, now())
            ON CONFLICT (usage_month, meter) DO UPDATE
               SET request_count = ev.provider_monthly_usage.request_count + 1,
                   updated_at = now()
             WHERE ev.provider_monthly_usage.request_count < ?
                OR EXISTS (SELECT 1 FROM ev.provider_limit_override o WHERE o.usage_month = ?)
            RETURNING request_count
            """;

    private static final String READ_MONTH = """
            SELECT meter, request_count, updated_at
              FROM ev.provider_monthly_usage
             WHERE usage_month = ?
            """;

    private static final String READ_OVERRIDE = """
            SELECT granted_at FROM ev.provider_limit_override WHERE usage_month = ?
            """;

    private static final String IGNORE_LIMITS = """
            INSERT INTO ev.provider_limit_override (usage_month, granted_by, granted_at)
            VALUES (?, ?, now())
            ON CONFLICT (usage_month) DO NOTHING
            """;

    private static final String RESTORE_LIMITS = """
            DELETE FROM ev.provider_limit_override WHERE usage_month = ?
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
        return !jdbcTemplate.queryForList(CONSUME, Integer.class, usageMonth, meter, limit, usageMonth).isEmpty();
    }

    @Override
    public List<MeterUsage> usageFor(LocalDate usageMonth) {
        return jdbcTemplate.query(READ_MONTH, (row, index) -> new MeterUsage(
                row.getString("meter"),
                row.getInt("request_count"),
                row.getTimestamp("updated_at").toInstant()
        ), usageMonth);
    }

    @Override
    public Optional<Instant> limitsIgnoredSince(LocalDate usageMonth) {
        return jdbcTemplate.queryForList(READ_OVERRIDE, Timestamp.class, usageMonth).stream()
                .findFirst()
                .map(Timestamp::toInstant);
    }

    @Override
    public void ignoreLimits(LocalDate usageMonth, UUID grantedBy) {
        jdbcTemplate.update(IGNORE_LIMITS, usageMonth, grantedBy);
    }

    @Override
    public void restoreLimits(LocalDate usageMonth) {
        jdbcTemplate.update(RESTORE_LIMITS, usageMonth);
    }
}
