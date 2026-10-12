package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.route.*;
import com.navio.mobilityandevservice.service.RouteService;
import com.navio.mobilityandevservice.domain.energy.CanonicalEnergy;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;

/** Search uses corridor approximations. Only a routed replay may be returned as feasible. */
final class OptimizedRouteVerifier {
    private record Stop(RoutePointRequest point, PlannedChargeStop charge) {}

    static OptimizationSearchResult verify(OptimizationRoute route, EvRouteOptimizationRequest request,
                                          OptimizationSearchResult search, RouteService routes) {
        List<Stop> stops = new ArrayList<>();
        var first = route.spans().getFirst().from();
        stops.add(new Stop(new RoutePointRequest(first.itemId(), first.name(), RoutePointType.PLACE, first.lat(), first.lng()), null));
        int index = 0;
        for (var span : route.spans()) {
            for (var charge : search.chargeStops().stream().filter(c -> c.spanIndex() == span.index()).toList()) {
                var charger = charge.candidate().charger();
                var previous = stops.getLast();
                if (previous.point().id().equals(charge.candidate().existingItemId())) {
                    stops.set(stops.size() - 1, new Stop(previous.point(), charge));
                } else {
                    var location = charger.location();
                    stops.add(new Stop(new RoutePointRequest("ev-verify-" + index++, charger.name(), RoutePointType.CHARGER,
                            location.lat(), location.lng()), charge));
                }
            }
            var end = span.to();
            stops.add(new Stop(new RoutePointRequest(end.itemId(), end.name(), RoutePointType.PLACE, end.lat(), end.lng()), null));
        }
        var segments = roadSegments(request.blockId(), stops.stream().map(Stop::point).toList(), routes);
        Map<Integer, Double> detours = new HashMap<>();
        // Measure each inserted charger's access detour against the road route bypassing that stop.
        for (int i = 1; i < stops.size() - 1; i++) {
            var charge = stops.get(i).charge();
            if (charge == null || request.stops().stream().anyMatch(s -> s.effectiveLocked()
                    && s.itemId().equals(charge.candidate().existingItemId()))) continue;
            var from = stops.get(i - 1).point();
            var at = stops.get(i).point();
            var to = stops.get(i + 1).point();
            var incoming = leg(segments, request.blockId(), from.id(), at.id());
            var outgoing = leg(segments, request.blockId(), at.id(), to.id());
            var bypass = leg(roadSegments(request.blockId(), List.of(from, to), routes), request.blockId(), from.id(), to.id());
            if (!routed(incoming) || !routed(outgoing) || !routed(bypass)) return failure("The charger access detour could not be verified on road routes.");
            double detour = Math.max(0, (incoming.distanceMeters() + outgoing.distanceMeters() - bypass.distanceMeters()) / 1000.0);
            if (detour > request.maximumDetourKm() + 1e-9) return failure("The selected charger exceeds the maximum detour on its actual road route.");
            detours.put(i, detour);
        }
        double soc = request.startingSocPct();
        long seconds = 0;
        double minutes = 0;
        boolean availabilityUnconfirmed = false;
        List<PlannedChargeStop> verified = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            if (i > 0) {
                String from = stops.get(i - 1).point().id();
                var leg = leg(segments, request.blockId(), from, stop.point().id());
                if (!routed(leg)) {
                    return failure("The selected charging itinerary could not be verified on road routes. Try again when routing is available.");
                }
                var energy = CanonicalEnergy.calculate(new CanonicalEnergy.Input(request.vehicle().resolvedModel(),
                        leg.distanceMeters() / 1000.0, "ROUTED", soc, null, null, (double) request.reserveSocPct()));
                if (energy.rawPredictedArrivalSocPct() == null) return failure("Canonical battery state is unavailable for the verified road route.");
                soc = energy.rawPredictedArrivalSocPct();
                seconds += leg.durationSeconds();
            }
            if (i > 0 && soc < -1e-9) {
                return failure("The selected charging stop or destination is unreachable on its actual road route with the available battery.");
            }
            if (i > 0 && soc + 1e-9 < request.reserveSocPct()) {
                return failure("The selected charging itinerary falls below reserve on its actual road route. No changes were applied; try another charger or a higher starting SOC.");
            }
            var observed = request.stops().stream().filter(value -> value.itemId().equals(stop.point().id()))
                    .map(value -> value.observedSocPct()).filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (observed != null) soc = observed;
            if (stop.charge() != null) {
                var charge = stop.charge();
                double departure = Math.max(soc, charge.departureSocPct());
                Double charging = SocConstrainedRouteOptimizer.chargeMinutes(soc, departure, charge.candidate().charger(), request.vehicle());
                if (charging == null || (departure > soc && charging == 0)) return failure("The selected charger has no confirmed usable charging power.");
                verified.add(new PlannedChargeStop(charge.spanIndex(), charge.sequence(), charge.beforeItemId(), charge.candidate(),
                        soc, departure, charging, detours.getOrDefault(i, 0.0)));
                soc = departure;
                minutes += charging;
                availabilityUnconfirmed |= charging > 0 && availabilityUnconfirmed(charge.candidate().charger());
            } else {
                // A retained/final charger can already have a saved target or duration, even when
                // search did not propose an operation there. Replay it rather than silently skip it.
                var saved = request.stops().stream().filter(s -> s.itemId().equals(stop.point().id()) && s.chargerStop()).findFirst().orElse(null);
                if (saved != null) {
                    if (saved.effectiveTargetSocPct() == null && saved.effectiveChargeMinutes() == null) {
                        return failure("A retained charging stop has no saved charge target or duration to verify.");
                    }
                    boolean compatible = SocConstrainedRouteOptimizer.compatible(saved.charger(), request.vehicle());
                    Double target = saved.effectiveTargetSocPct();
                    Double duration = saved.effectiveChargeMinutes();
                    boolean requiresCharge = target != null ? target > soc : duration != null && duration > 0;
                    if (requiresCharge && !compatible) return failure("A saved charging stop has no confirmed active compatible connector.");
                    var energy = CanonicalEnergy.calculate(new CanonicalEnergy.Input(request.vehicle().resolvedModel(), 0.0, "ROUTED", soc, null,
                            new CanonicalEnergy.Charging(target, duration, compatible, SocConstrainedRouteOptimizer.effectivePowerKw(saved.charger(), request.vehicle())), request.reserveSocPct()));
                    if (energy.departureSocPct() == null || energy.nominalChargeMinutes() == null) return failure("A saved charging stop has unavailable battery or charging power data.");
                    soc = energy.departureSocPct();
                    minutes += energy.nominalChargeMinutes();
                    availabilityUnconfirmed |= energy.nominalChargeMinutes() > 0 && availabilityUnconfirmed(saved.charger());
                }
            }
        }
        if (soc + 1e-9 < request.reserveSocPct()) return failure("The final observed battery state is below the selected reserve; review the final stop before applying a charging plan.");
        List<String> warnings = new ArrayList<>(search.warnings());
        warnings.removeIf(warning -> warning.startsWith("FALLBACK_DISTANCE:"));
        warnings.add("Verified using " + CanonicalEnergy.VERSION + ": nominal energy; reserve is separate. Rated-range results remain preview-only.");
        warnings.add("The selected stops were checked on road routes. Search uses a limited charger corridor and 5% charging targets; a global minimum travel time is not guaranteed. Allow 5 extra minutes per charging stop to connect.");
        if (availabilityUnconfirmed) {
            warnings.add("Charger availability is unknown, stale or currently busy; route feasibility does not confirm a free working connector on arrival.");
        }
        return new OptimizationSearchResult(true, soc, seconds, minutes, verified, warnings);
    }

    private static boolean availabilityUnconfirmed(com.navio.mobilityandevservice.domain.ev.EvChargerResponse charger) {
        return charger.stale() || charger.availableConnectors() == null || charger.availableConnectors() <= 0;
    }

    private static List<RouteSegmentResponse> roadSegments(String blockId, List<RoutePointRequest> points, RouteService routes) {
        var segments = new ArrayList<RouteSegmentResponse>();
        for (int start = 0; start < points.size() - 1; start += 24) {
            segments.addAll(routes.computeDirections(new DirectionsRequest(RouteProfile.DRIVING_TRAFFIC,
                    List.of(new RoutePointGroupRequest(blockId, points.subList(start, Math.min(start + 25, points.size())))))).segments());
        }
        return segments;
    }

    private static RouteSegmentResponse leg(List<RouteSegmentResponse> segments, String blockId, String from, String to) {
        var matches = segments.stream().filter(s -> blockId.equals(s.blockId()) && from.equals(s.fromItemId()) && to.equals(s.toItemId())).toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean routed(RouteSegmentResponse leg) {
        return leg != null && leg.status() == RouteSegmentStatus.ROUTED && leg.distanceMeters() != null
                && leg.durationSeconds() != null && leg.distanceMeters() >= 0 && leg.durationSeconds() >= 0;
    }

    private static OptimizationSearchResult failure(String message) {
        return new OptimizationSearchResult(false, null, 0, 0, List.of(), List.of(message));
    }
}
