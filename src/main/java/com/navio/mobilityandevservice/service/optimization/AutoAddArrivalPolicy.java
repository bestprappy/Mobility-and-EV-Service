package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;

/**
 * The battery a plan must keep when it arrives somewhere.
 *
 * <p>For Auto Add the Battery Threshold is a trigger, not a floor everywhere: falling below it before an
 * itinerary stop is what makes a charger necessary, so the charger itself may be reached below it. Clients
 * that still send a Minimum Arrival Battery use it for the final destination and as the charger floor.
 * Threshold-only clients omit it: every itinerary stop, including the final one, then needs the threshold,
 * and a charger may be reached down to {@link #CHARGER_FLOOR_SOC_PCT}. Other optimization keeps the reserve
 * as a floor at every arrival.</p>
 */
final class AutoAddArrivalPolicy {
    /** The lowest battery a threshold-only Auto Add lets the car reach a charger with. */
    static final double CHARGER_FLOOR_SOC_PCT = 10.0;

    private AutoAddArrivalPolicy() {}

    static double requiredArrivalSocPct(EvRouteOptimizationRequest request, boolean charger, boolean finalDestination) {
        Double minimumArrival = request.minimumArrivalSocPct();
        if (!Boolean.TRUE.equals(request.insertionOnly())) {
            return Math.max(request.reserveSocPct(), finalDestination && minimumArrival != null ? minimumArrival : 0);
        }
        if (finalDestination) return minimumArrival != null ? minimumArrival : request.reserveSocPct();
        if (charger) return chargerFloorSocPct(request);
        return request.reserveSocPct();
    }

    /** The lowest battery Auto Add lets the car reach a charger with. */
    static double chargerFloorSocPct(EvRouteOptimizationRequest request) {
        Double minimumArrival = request.minimumArrivalSocPct();
        double floor = minimumArrival == null ? CHARGER_FLOOR_SOC_PCT : minimumArrival;
        return Math.min(request.reserveSocPct(), floor);
    }
}
