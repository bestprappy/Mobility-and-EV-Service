package com.navio.mobilityandevservice.domain.usage;

import java.time.Instant;
import java.util.List;

/**
 * This billing month's counted Google requests against Navio's own limits.
 *
 * @param limitsEnforced         false when calls no longer stop at {@code limit}
 * @param placesDiscoveryEnabled whether Explore's Places discovery may call Google
 * @param limitsIgnoredSince     when an administrator let this month's calls continue past
 *                               {@code limit}; null while calls stop at the limit
 */
public record GoogleUsageResponse(
        Instant monthStart,
        Instant resetsAt,
        Instant asOf,
        boolean limitsEnforced,
        boolean placesDiscoveryEnabled,
        Instant limitsIgnoredSince,
        List<Meter> meters
) {
    /** @param lastCountedAt null when this meter has no calls yet this month */
    public record Meter(String meter, int used, int limit, Instant lastCountedAt) {
    }
}
