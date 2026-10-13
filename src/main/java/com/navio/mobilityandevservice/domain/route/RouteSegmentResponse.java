package com.navio.mobilityandevservice.domain.route;

import java.util.List;

public record RouteSegmentResponse(
        String id,
        String blockId,
        String fromItemId,
        String toItemId,
        String fromName,
        String toName,
        RouteSegmentStatus status,
        RouteLineString geometry,
        Long distanceMeters,
        Long durationSeconds,
        String fallbackReason,
        LegTravelMode travelMode,
        List<RouteStepResponse> steps,
        List<TransitJourneyResponse> journeys
) {

    public RouteSegmentResponse {
        travelMode = travelMode == null ? LegTravelMode.DRIVE : travelMode;
        steps = steps == null ? List.of() : List.copyOf(steps);
        journeys = journeys == null ? List.of() : List.copyOf(journeys);
    }

    public RouteSegmentResponse(String id, String blockId, String fromItemId, String toItemId,
                                String fromName, String toName, RouteSegmentStatus status,
                                RouteLineString geometry, Long distanceMeters, Long durationSeconds,
                                String fallbackReason, LegTravelMode travelMode, List<RouteStepResponse> steps) {
        this(id, blockId, fromItemId, toItemId, fromName, toName, status, geometry, distanceMeters,
                durationSeconds, fallbackReason, travelMode, steps, List.of());
    }

    public RouteSegmentResponse(
            String id,
            String blockId,
            String fromItemId,
            String toItemId,
            String fromName,
            String toName,
            RouteSegmentStatus status,
            RouteLineString geometry,
            Long distanceMeters,
            Long durationSeconds,
            String fallbackReason
    ) {
        this(id, blockId, fromItemId, toItemId, fromName, toName, status, geometry,
                distanceMeters, durationSeconds, fallbackReason, LegTravelMode.DRIVE, List.of());
    }
}
