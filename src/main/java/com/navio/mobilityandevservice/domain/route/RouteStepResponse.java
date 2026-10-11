package com.navio.mobilityandevservice.domain.route;

/**
 * One part of a transit leg: a ride on a single line, or a walk between stops.
 *
 * @param line           present only for {@link LegTravelMode#TRANSIT} steps
 * @param durationSeconds time moving, excluding any wait for the vehicle
 */
public record RouteStepResponse(
        LegTravelMode travelMode,
        RouteLineString geometry,
        Long distanceMeters,
        Long durationSeconds,
        TransitLineResponse line,
        String departureStop,
        String arrivalStop,
        Integer stopCount
) {
}
