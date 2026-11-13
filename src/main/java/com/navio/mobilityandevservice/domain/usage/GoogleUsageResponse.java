package com.navio.mobilityandevservice.domain.usage;

import java.time.Instant;
import java.util.List;

/**
 * This billing month's counted Google requests against Navio's own limits.
 *
 * @param limitsEnforced         false when calls no longer stop at {@code limit}
 * @param placesDiscoveryEnabled whether Explore's Places discovery may call Google
 */
public record GoogleUsageResponse(
        Instant monthStart,
        Instant resetsAt,
        Instant asOf,
        boolean limitsEnforced,
        boolean placesDiscoveryEnabled,
        List<Meter> meters
) {
    /** @param lastCountedAt null when this meter has no calls yet this month */
    public record Meter(String meter, int used, int limit, Instant lastCountedAt) {
    }
}
