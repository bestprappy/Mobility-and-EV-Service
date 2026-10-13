package com.navio.mobilityandevservice.domain.route;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** Provider-independent, scheduled door-to-door public transport suggestion. */
public record TransitJourneyResponse(
        String id,
        String provider,
        RouteLineString geometry,
        Long distanceMeters,
        Long durationSeconds,
        Long walkingSeconds,
        Instant departureTime,
        Instant arrivalTime,
        String timeZone,
        BigDecimal fare,
        String currency,
        List<RouteStepResponse> steps
) {
    public TransitJourneyResponse {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
