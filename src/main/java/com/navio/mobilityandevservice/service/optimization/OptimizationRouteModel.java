package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.optimization.EvRouteStopRequest;
import com.navio.mobilityandevservice.domain.route.RouteCoordinate;

import java.util.List;

record OptimizationRoute(List<OptimizationRouteSpan> spans) {
    OptimizationRoute {
        spans = List.copyOf(spans);
    }
}

record OptimizationRouteSpan(
        int index,
        EvRouteStopRequest from,
        EvRouteStopRequest to,
        double distanceKm,
        long durationSeconds,
        List<RouteCoordinate> geometry,
        List<OptimizationChargerCandidate> candidates
) {
    OptimizationRouteSpan {
        geometry = List.copyOf(geometry);
        candidates = List.copyOf(candidates);
    }

    OptimizationRouteSpan withCandidates(List<OptimizationChargerCandidate> nextCandidates) {
        return new OptimizationRouteSpan(
                index,
                from,
                to,
                distanceKm,
                durationSeconds,
                geometry,
                nextCandidates
        );
    }
}

record OptimizationChargerCandidate(
        EvChargerResponse charger,
        double progressKm,
        double deviationKm,
        String existingItemId
) {
}

record PlannedChargeStop(
        int spanIndex,
        int sequence,
        String beforeItemId,
        OptimizationChargerCandidate candidate,
        int arrivalSocPct,
        int departureSocPct,
        int chargeMinutes,
        double detourKm
) {
}

record OptimizationSearchResult(
        boolean feasible,
        int finalSocPct,
        long totalDrivingSeconds,
        int totalChargingMinutes,
        List<PlannedChargeStop> chargeStops,
        List<String> warnings
) {
    OptimizationSearchResult {
        chargeStops = List.copyOf(chargeStops);
        warnings = List.copyOf(warnings);
    }
}
