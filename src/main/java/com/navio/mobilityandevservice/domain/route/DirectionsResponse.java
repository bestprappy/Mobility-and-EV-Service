package com.navio.mobilityandevservice.domain.route;

import java.util.List;

public record DirectionsResponse(List<RouteSegmentResponse> segments) {

    public DirectionsResponse {
        segments = segments == null ? List.of() : List.copyOf(segments);
    }
}
