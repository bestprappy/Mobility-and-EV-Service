package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.optimization.EvPlanOperation;
import com.navio.mobilityandevservice.domain.optimization.EvPlanOperationType;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationResponse;
import com.navio.mobilityandevservice.domain.optimization.EvRouteStopRequest;
import com.navio.mobilityandevservice.domain.route.DirectionsRequest;
import com.navio.mobilityandevservice.domain.route.DirectionsResponse;
import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import com.navio.mobilityandevservice.domain.route.RoutePointGroupRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointType;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import com.navio.mobilityandevservice.domain.route.RouteSegmentResponse;
import com.navio.mobilityandevservice.service.RouteService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class EvRouteOptimizationService {

    private static final double DEFAULT_FALLBACK_SPEED_KMH = 60;

    private final RouteService routeService;
    private final RouteCorridorChargerFinder chargerFinder;
    private final SocConstrainedRouteOptimizer routeOptimizer;

    public EvRouteOptimizationService(
            RouteService routeService,
            RouteCorridorChargerFinder chargerFinder,
            SocConstrainedRouteOptimizer routeOptimizer
    ) {
        this.routeService = routeService;
        this.chargerFinder = chargerFinder;
        this.routeOptimizer = routeOptimizer;
    }

    public EvRouteOptimizationResponse optimize(EvRouteOptimizationRequest request) {
        List<EvRouteStopRequest> mandatoryStops = request.stops().stream()
                .filter(stop -> !stop.chargerStop() || stop.effectiveLocked())
                .toList();
        List<EvRouteStopRequest> replaceableStops = request.stops().stream()
                .filter(stop -> stop.chargerStop() && !stop.effectiveLocked())
                .toList();
        if (mandatoryStops.size() < 2) {
            return new EvRouteOptimizationResponse(
                    request.blockId(),
                    false,
                    List.of(),
                    (int) Math.floor(request.startingSocPct()),
                    0,
                    0,
                    "Add at least a starting place and destination before optimizing the EV route.",
                    List.of("The route does not contain two mandatory places.")
            );
        }

        OptimizationRoute baseRoute = computeBaseRoute(request.blockId(), mandatoryStops);
        OptimizationRoute routeWithCandidates = chargerFinder.populateCandidates(
                baseRoute,
                request,
                replaceableStops
        );
        OptimizationSearchResult result = routeOptimizer.optimize(routeWithCandidates, request);
        if (result.feasible()) {
            result = OptimizedRouteVerifier.verify(routeWithCandidates, request, result, routeService);
        }
        if (!result.feasible()) {
            return new EvRouteOptimizationResponse(
                    request.blockId(),
                    false,
                    List.of(),
                    result.finalSocPct(),
                    result.totalDrivingSeconds(),
                    result.totalChargingMinutes(),
                    "No safe compatible charging plan could be found.",
                    result.warnings()
            );
        }

        List<EvPlanOperation> operations = buildOperations(request, result.chargeStops());
        String message = operations.isEmpty()
                ? "The current charging plan is already efficient."
                : "Found " + operations.size() + " EV route change" + (operations.size() == 1 ? "." : "s.");
        return new EvRouteOptimizationResponse(
                request.blockId(),
                true,
                operations,
                result.finalSocPct(),
                result.totalDrivingSeconds(),
                result.totalChargingMinutes(),
                message,
                result.warnings()
        );
    }

    private OptimizationRoute computeBaseRoute(String blockId, List<EvRouteStopRequest> mandatoryStops) {
        List<RoutePointRequest> points = mandatoryStops.stream()
                .map(stop -> new RoutePointRequest(
                        stop.itemId(),
                        stop.name(),
                        stop.chargerStop() ? RoutePointType.CHARGER : RoutePointType.PLACE,
                        stop.lat(),
                        stop.lng()
                ))
                .toList();
        DirectionsResponse directions = routeService.computeDirections(new DirectionsRequest(
                RouteProfile.DRIVING_TRAFFIC,
                List.of(new RoutePointGroupRequest(blockId, points))
        ));
        Map<String, RouteSegmentResponse> segmentsByEndpoints = directions.segments().stream()
                .collect(LinkedHashMap::new, (map, segment) -> map.put(
                        segmentKey(segment.fromItemId(), segment.toItemId()),
                        segment
                ), Map::putAll);

        List<OptimizationRouteSpan> spans = new ArrayList<>();
        for (int index = 0; index < mandatoryStops.size() - 1; index++) {
            EvRouteStopRequest from = mandatoryStops.get(index);
            EvRouteStopRequest to = mandatoryStops.get(index + 1);
            RouteSegmentResponse segment = segmentsByEndpoints.get(segmentKey(from.itemId(), to.itemId()));
            double distanceKm = segment != null && segment.distanceMeters() != null
                    ? segment.distanceMeters() / 1_000.0
                    : directDistanceKm(from, to);
            long durationSeconds = segment != null && segment.durationSeconds() != null
                    ? segment.durationSeconds()
                    : Math.round(distanceKm / DEFAULT_FALLBACK_SPEED_KMH * 3_600);
            List<RouteCoordinate> geometry = segment != null && segment.geometry() != null
                    ? segment.geometry().coordinates()
                    : List.of(
                            new RouteCoordinate(from.lng(), from.lat()),
                            new RouteCoordinate(to.lng(), to.lat())
                    );
            spans.add(new OptimizationRouteSpan(
                    index,
                    from,
                    to,
                    distanceKm,
                    durationSeconds,
                    geometry,
                    List.of()
            ));
        }
        return new OptimizationRoute(spans);
    }

    private List<EvPlanOperation> buildOperations(
            EvRouteOptimizationRequest request,
            List<PlannedChargeStop> plannedStops
    ) {
        Map<Integer, List<EvRouteStopRequest>> existingBySpan = existingReplaceableStopsBySpan(request.stops());
        Map<Integer, List<PlannedChargeStop>> plannedBySpan = new HashMap<>();
        for (PlannedChargeStop stop : plannedStops) {
            plannedBySpan.computeIfAbsent(stop.spanIndex(), ignored -> new ArrayList<>()).add(stop);
        }
        // Retention is decided across the whole route, not per span. A charger can project onto a
        // different span than the one it currently sits in, and a per-span check would emit a
        // removal in its old span while another span still plans to charge there.
        Set<String> retainedItemIds = plannedStops.stream()
                .map(stop -> stop.candidate().existingItemId())
                .filter(Objects::nonNull)
                .collect(HashSet::new, Set::add, Set::addAll);

        List<EvPlanOperation> operations = new ArrayList<>();
        int maximumSpan = Math.max(
                existingBySpan.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1),
                plannedBySpan.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1)
        );
        for (int spanIndex = 0; spanIndex <= maximumSpan; spanIndex++) {
            List<EvRouteStopRequest> existing = existingBySpan.getOrDefault(spanIndex, List.of());
            List<PlannedChargeStop> planned = plannedBySpan.getOrDefault(spanIndex, List.of());
            List<EvRouteStopRequest> removals = existing.stream()
                    .filter(stop -> !retainedItemIds.contains(stop.itemId()))
                    .toList();
            List<PlannedChargeStop> additions = planned.stream()
                    .filter(stop -> stop.candidate().existingItemId() == null)
                    .toList();

            int pairedChanges = Math.min(removals.size(), additions.size());
            for (int index = 0; index < pairedChanges; index++) {
                PlannedChargeStop addition = additions.get(index);
                operations.add(operation(
                        EvPlanOperationType.REPLACE_CHARGER,
                        removals.get(index).itemId(),
                        addition,
                        "Replaces a slower, unavailable, or less reliable charging stop."
                ));
            }
            for (int index = pairedChanges; index < removals.size(); index++) {
                EvRouteStopRequest removal = removals.get(index);
                operations.add(new EvPlanOperation(
                        EvPlanOperationType.REMOVE_CHARGER,
                        removal.itemId(),
                        null,
                        index,
                        null,
                        0,
                        0,
                        0,
                        0,
                        "This charging stop is not needed by the optimized route."
                ));
            }
            for (int index = pairedChanges; index < additions.size(); index++) {
                operations.add(operation(
                        EvPlanOperationType.ADD_CHARGER,
                        null,
                        additions.get(index),
                        "Adds a reachable compatible charging stop."
                ));
            }
            for (PlannedChargeStop retained : planned) {
                if (retained.candidate().existingItemId() == null) {
                    continue;
                }
                operations.add(operation(
                        EvPlanOperationType.UPDATE_CHARGER,
                        retained.candidate().existingItemId(),
                        retained,
                        "Refreshes the charger snapshot and charging duration."
                ));
            }
        }
        return List.copyOf(operations);
    }

    private EvPlanOperation operation(
            EvPlanOperationType type,
            String oldItemId,
            PlannedChargeStop stop,
            String reason
    ) {
        return new EvPlanOperation(
                type,
                oldItemId,
                stop.beforeItemId(),
                stop.sequence(),
                stop.candidate().charger(),
                stop.chargeMinutes(),
                stop.arrivalSocPct(),
                stop.departureSocPct(),
                stop.detourKm(),
                reason
        );
    }

    private Map<Integer, List<EvRouteStopRequest>> existingReplaceableStopsBySpan(
            List<EvRouteStopRequest> stops
    ) {
        Map<Integer, List<EvRouteStopRequest>> stopsBySpan = new HashMap<>();
        int mandatoryIndex = -1;
        for (EvRouteStopRequest stop : stops) {
            if (!stop.chargerStop() || stop.effectiveLocked()) {
                mandatoryIndex++;
                continue;
            }
            int spanIndex = Math.max(0, mandatoryIndex);
            stopsBySpan.computeIfAbsent(spanIndex, ignored -> new ArrayList<>()).add(stop);
        }
        return stopsBySpan;
    }

    private String segmentKey(String fromItemId, String toItemId) {
        return fromItemId + "\u0000" + toItemId;
    }

    private double directDistanceKm(EvRouteStopRequest from, EvRouteStopRequest to) {
        double earthRadiusKm = 6_371;
        double fromLat = Math.toRadians(from.lat());
        double toLat = Math.toRadians(to.lat());
        double deltaLat = Math.toRadians(to.lat() - from.lat());
        double deltaLng = Math.toRadians(to.lng() - from.lng());
        double haversine = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(fromLat) * Math.cos(toLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return earthRadiusKm * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }
}
