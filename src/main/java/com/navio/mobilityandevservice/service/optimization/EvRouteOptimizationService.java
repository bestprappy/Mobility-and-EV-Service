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
    private static final double UNCHANGED_TARGET_TOLERANCE_PCT = 0.5;

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
        var preceding = PrecedingTripEnergy.resolve(request, routeService);
        if (preceding.failure() != null) {
            return new EvRouteOptimizationResponse(request.blockId(), false, List.of(), null, 0, 0,
                    preceding.failure(), List.of(preceding.failure()));
        }
        var stops = new ArrayList<>(request.stops());
        if (!stops.isEmpty()) {
            stops.set(0, protectEndpoint(stops.getFirst()));
            stops.set(stops.size() - 1, protectEndpoint(stops.getLast()));
        }
        return optimizeDay(new EvRouteOptimizationRequest(request.blockId(), stops, request.vehicle(),
                preceding.socPct(), request.reserveSocPct(), request.targetSocPct(), request.maximumDetourKm()),
                request.followingDays(), request.precedingDays().size() + 1);
    }

    /** Replays the later days from where and with what the selected day ends. */
    private PrecedingTripEnergy.State laterDays(EvRouteOptimizationRequest request, OptimizationSearchResult plan,
                                                List<EvRouteOptimizationRequest.Day> followingDays) {
        if (followingDays.isEmpty()) return new PrecedingTripEnergy.State(plan.finalSocPct(), null, false);
        var last = request.stops().getLast();
        // Carry only the selected day's final state/position; do not charge or observe its last stop twice.
        var boundary = new EvRouteStopRequest(last.itemId(), last.name(), last.lat(), last.lng(), null, false, null);
        var replay = new ArrayList<EvRouteOptimizationRequest.Day>();
        replay.add(new EvRouteOptimizationRequest.Day(request.blockId(), List.of(boundary)));
        replay.addAll(followingDays);
        var end = replay.stream().filter(day -> !day.stops().isEmpty()).reduce((a, b) -> b).orElseThrow().stops().getLast();
        return PrecedingTripEnergy.resolveLaterDays(new EvRouteOptimizationRequest(request.blockId(), List.of(end), request.vehicle(),
                plan.finalSocPct(), request.reserveSocPct(), request.targetSocPct(), request.maximumDetourKm(), replay), routeService);
    }

    /** Replay index 0 is the selected day's end, so an index counts forward from the selected day. */
    private static List<String> uncheckedDayWarning(PrecedingTripEnergy.State later, int selectedDayNumber, int followingDayCount) {
        if (later.uncheckedDayIndex() == null) return List.of();
        int index = later.uncheckedDayIndex();
        return List.of("Day " + (selectedDayNumber + index) + " doesn't start where the day before it ends, so its charge wasn't checked."
                + (index < followingDayCount ? " Later days weren't checked either." : ""));
    }

    private EvRouteStopRequest protectEndpoint(EvRouteStopRequest stop) {
        if (!stop.chargerStop()) return stop;
        // The actual day boundary cannot move when an unlocked charger is replaced.
        // This is request-local protection, not a change to the saved user's lock setting.
        return new EvRouteStopRequest(stop.itemId(), stop.name(), stop.lat(), stop.lng(), stop.charger(),
                true, stop.selectionSource(), stop.targetBatteryPct(), stop.observedSocPct(), stop.estimatedChargeMinutes(), stop.chargingDurationMinutes(), stop.plannedDepartureSocPct());
    }

    private EvRouteOptimizationResponse optimizeDay(EvRouteOptimizationRequest request,
                                                    List<EvRouteOptimizationRequest.Day> followingDays,
                                                    int selectedDayNumber) {
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
                    request.startingSocPct(),
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
        OptimizationSearchResult search = routeOptimizer.optimize(routeWithCandidates, request);
        if (!search.feasible()) return infeasible(request, routeWithCandidates, search);

        // Search works from corridor estimates and stops at the cheapest plan for this day alone. A
        // plan that charges more can still pass where the cheapest fails the road check or the next day.
        List<OptimizationSearchResult> plans = new ArrayList<>();
        plans.add(search);
        plans.addAll(search.costlierAlternatives());
        OptimizationSearchResult firstRejected = null;
        OptimizationSearchResult withShortfallLater = null;
        List<String> shortfallUncheckedWarning = List.of();
        for (OptimizationSearchResult plan : plans) {
            OptimizationSearchResult verified = OptimizedRouteVerifier.verify(routeWithCandidates, request, plan, routeService);
            if (!verified.feasible()) {
                if (firstRejected == null) firstRejected = verified;
                continue;
            }
            var later = laterDays(request, verified, followingDays);
            List<String> unchecked = uncheckedDayWarning(later, selectedDayNumber, followingDays.size());
            if (later.failure() == null) return feasible(request, routeWithCandidates, verified, unchecked);
            if (!later.batteryShortfall()) {
                // A gap, unknown route or low observed reading on a later day is not fixed by charging more today.
                String message = "The proposed plan leaves a later day unavailable, disconnected or below reserve. Review the later itinerary before applying.";
                return new EvRouteOptimizationResponse(request.blockId(), false, List.of(), verified.finalSocPct(),
                        verified.totalDrivingSeconds(), verified.totalChargingMinutes(), message, List.of(message, later.failure()));
            }
            if (withShortfallLater == null) {
                withShortfallLater = verified;
                shortfallUncheckedWarning = unchecked;
            }
        }
        if (withShortfallLater != null) {
            // Days plan in order. Refusing here would deadlock: the later day refuses until this one is safe.
            List<String> extra = new ArrayList<>(List.of("A later day still runs short of battery with this plan. Plan charging for that day next."));
            extra.addAll(shortfallUncheckedWarning);
            return feasible(request, routeWithCandidates, withShortfallLater, extra);
        }
        return infeasible(request, routeWithCandidates, firstRejected);
    }

    private EvRouteOptimizationResponse infeasible(EvRouteOptimizationRequest request, OptimizationRoute route, OptimizationSearchResult result) {
        return new EvRouteOptimizationResponse(
                request.blockId(),
                false,
                List.of(),
                result.finalSocPct(),
                result.totalDrivingSeconds(),
                result.totalChargingMinutes(),
                route.failedChargerSearches() > 0
                        ? "Charger search is temporarily unavailable, so a charging plan could not be found. Try again shortly."
                        : "No safe compatible charging plan could be found.",
                result.warnings()
        );
    }

    private EvRouteOptimizationResponse feasible(EvRouteOptimizationRequest request, OptimizationRoute route,
                                                 OptimizationSearchResult result, List<String> extraWarnings) {
        List<EvPlanOperation> operations = buildOperations(request, result.chargeStops());
        long changes = operations.stream().filter(operation -> changesThePlan(operation, request)).count();
        String message = changes == 0
                ? "The current charging plan is already efficient."
                : "Found " + changes + " EV route change" + (changes == 1 ? "." : "s.");
        List<String> warnings = new ArrayList<>(result.warnings());
        warnings.addAll(extraWarnings);
        if (route.failedChargerSearches() > 0) {
            warnings.add("Part of the charger search was unavailable, so a closer or faster charger may be missing from this plan.");
        }
        return new EvRouteOptimizationResponse(
                request.blockId(),
                true,
                operations,
                result.finalSocPct(),
                result.totalDrivingSeconds(),
                result.totalChargingMinutes(),
                message,
                warnings
        );
    }

    /** Every kept stop gets an update to refresh its snapshot; only a different charge target is a change. */
    private boolean changesThePlan(EvPlanOperation operation, EvRouteOptimizationRequest request) {
        if (operation.type() != EvPlanOperationType.UPDATE_CHARGER) return true;
        Double savedTarget = request.stops().stream()
                .filter(stop -> stop.itemId().equals(operation.oldItemId()))
                .map(EvRouteStopRequest::effectiveTargetSocPct)
                .filter(Objects::nonNull)
                .findFirst().orElse(null);
        return savedTarget == null || Math.abs(savedTarget - operation.departureSocPct()) > UNCHANGED_TARGET_TOLERANCE_PCT;
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
                    List.of(),
                    segment != null && segment.distanceMeters() != null && segment.status() == com.navio.mobilityandevservice.domain.route.RouteSegmentStatus.ROUTED ? "ROUTED" : "FALLBACK"
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
