package com.navio.mobilityandevservice.domain.route;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.List;

public record RouteCoordinate(double lng, double lat) {

    @JsonValue
    public List<Double> asGeoJsonPosition() {
        return List.of(lng, lat);
    }
}
