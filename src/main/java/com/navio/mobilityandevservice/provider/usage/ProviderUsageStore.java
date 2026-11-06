package com.navio.mobilityandevservice.provider.usage;

import java.time.LocalDate;

/** Durable request counter shared by every service instance. */
public interface ProviderUsageStore {

    /**
     * Atomically counts one request for {@code meter} in the month starting on {@code usageMonth}.
     *
     * @return {@code false}, without counting, when the month already reached {@code limit}
     */
    boolean tryConsume(LocalDate usageMonth, String meter, int limit);
}
