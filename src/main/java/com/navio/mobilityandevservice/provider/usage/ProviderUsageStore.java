package com.navio.mobilityandevservice.provider.usage;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable request counter shared by every service instance. */
public interface ProviderUsageStore {

    /**
     * Atomically counts one request for {@code meter} in the month starting on {@code usageMonth}.
     * In a month whose limits were ignored ({@link #ignoreLimits}) every request is counted.
     *
     * @return {@code false}, without counting, when the month already reached {@code limit}
     */
    boolean tryConsume(LocalDate usageMonth, String meter, int limit);

    /** Every meter counted in the month starting on {@code usageMonth}; meters with no calls are absent. */
    List<MeterUsage> usageFor(LocalDate usageMonth);

    /** When an administrator chose to ignore the limits of this month; empty while they apply. */
    Optional<Instant> limitsIgnoredSince(LocalDate usageMonth);

    /** Lets calls in this month continue past their limits. Repeating it keeps the first time. */
    void ignoreLimits(LocalDate usageMonth, UUID grantedBy);

    /** Stops calls at their limits again for this month. */
    void restoreLimits(LocalDate usageMonth);

    record MeterUsage(String meter, int requestCount, Instant updatedAt) {
    }
}
