package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.domain.route.RouteCoordinate;

import java.util.List;

public record RouteLeg(
        List<RouteCoordinate> coordinates,
        long distanceMeters,
        long durationSeconds
) {

    public RouteLeg {
        coordinates = coordinates == null ? List.of() : List.copyOf(coordinates);
    }
}
