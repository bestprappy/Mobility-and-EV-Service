package com.navio.mobilityandevservice.domain.route;

import java.util.List;

public record RouteLineString(String type, List<RouteCoordinate> coordinates) {

    public RouteLineString {
        type = "LineString";
        coordinates = coordinates == null ? List.of() : List.copyOf(coordinates);
    }

    public RouteLineString(List<RouteCoordinate> coordinates) {
        this("LineString", coordinates);
    }
}
