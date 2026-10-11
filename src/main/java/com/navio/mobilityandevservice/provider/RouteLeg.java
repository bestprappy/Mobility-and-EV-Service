package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import com.navio.mobilityandevservice.domain.route.RouteStepResponse;

import java.util.List;

/** @param steps ride and walk parts of a transit leg; empty for driving and walking legs */
public record RouteLeg(
        List<RouteCoordinate> coordinates,
        long distanceMeters,
        long durationSeconds,
        List<RouteStepResponse> steps
) {

    public RouteLeg {
        coordinates = coordinates == null ? List.of() : List.copyOf(coordinates);
        steps = steps == null ? List.of() : List.copyOf(steps);
    }

    public RouteLeg(List<RouteCoordinate> coordinates, long distanceMeters, long durationSeconds) {
        this(coordinates, distanceMeters, durationSeconds, List.of());
    }
}
