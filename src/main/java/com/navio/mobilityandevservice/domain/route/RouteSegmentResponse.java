package com.navio.mobilityandevservice.domain.route;

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
        String fallbackReason
) {
}
