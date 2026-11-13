package com.navio.mobilityandevservice.provider.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Durable request counter shared by every service instance. */
public interface ProviderUsageStore {

    /**
     * Atomically counts one request for {@code meter} in the month starting on {@code usageMonth}.
     *
     * @return {@code false}, without counting, when the month already reached {@code limit}
     */
    boolean tryConsume(LocalDate usageMonth, String meter, int limit);

    /** Every meter counted in the month starting on {@code usageMonth}; meters with no calls are absent. */
    List<MeterUsage> usageFor(LocalDate usageMonth);

    record MeterUsage(String meter, int requestCount, Instant updatedAt) {
    }
}
