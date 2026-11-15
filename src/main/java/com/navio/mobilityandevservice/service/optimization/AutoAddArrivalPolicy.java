package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;

/**
 * The battery a plan must keep when it arrives somewhere.
 *
 * <p>For Auto Add the Battery Threshold is a trigger, not a floor everywhere: falling below it before an
 * itinerary stop is what makes a charger necessary, so the charger itself may be reached below it, down to
 * the lower of the two preferences. The day's final destination only needs the Minimum Arrival Battery.
 * Other optimization keeps the reserve as a floor at every arrival.</p>
 */
final class AutoAddArrivalPolicy {
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
        return minimumArrival == null ? request.reserveSocPct() : Math.min(request.reserveSocPct(), minimumArrival);
    }
}
