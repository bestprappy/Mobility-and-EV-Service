package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.energy.CanonicalEnergy;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.optimization.EvRouteStopRequest;
import com.navio.mobilityandevservice.domain.optimization.EvVehicleSpec;
import com.navio.mobilityandevservice.domain.route.*;
import com.navio.mobilityandevservice.service.RouteService;

import java.util.List;
import java.util.Set;

/** Replay saved earlier days unchanged; only the selected day may be optimized. */
final class PrecedingTripEnergy {
    private static final Set<EvConnectorType> DC = Set.of(EvConnectorType.CCS1, EvConnectorType.CCS2,
            EvConnectorType.CHADEMO, EvConnectorType.NACS, EvConnectorType.GB_T);
    record State(Double socPct, String failure) {}
    private PrecedingTripEnergy() {}

    static State resolve(EvRouteOptimizationRequest request, RouteService routes) {
        Double forward = request.startingSocPct();
        EvRouteStopRequest previous = null;
        boolean uncertainOrInfeasible = false;
        for (var day : request.precedingDays()) {
            List<RouteSegmentResponse> segments = day.stops().size() < 2 ? List.of()
                    : routes.computeDirections(new DirectionsRequest(RouteProfile.DRIVING_TRAFFIC,
                    List.of(new RoutePointGroupRequest(day.blockId(), day.stops().stream()
                            .map(PrecedingTripEnergy::point).toList())))).segments();
            for (int i = 0; i < day.stops().size(); i++) {
                var stop = day.stops().get(i);
                Double km = null;
                if (i == 0) {
                    if (previous == null || samePosition(previous, stop)) km = 0.0;
                } else {
                    String from = day.stops().get(i - 1).itemId();
                    var leg = segments.stream().filter(s -> day.blockId().equals(s.blockId())
                            && from.equals(s.fromItemId()) && stop.itemId().equals(s.toItemId())).findFirst().orElse(null);
                    if (leg != null && leg.status() == RouteSegmentStatus.ROUTED && leg.distanceMeters() != null
                            && leg.distanceMeters() >= 0 && Double.isFinite(leg.distanceMeters())) km = leg.distanceMeters() / 1000.0;
                }
                var result = CanonicalEnergy.calculate(new CanonicalEnergy.Input(request.vehicle().resolvedModel(),
                        km, km == null ? "UNAVAILABLE" : "ROUTED", forward, stop.observedSocPct(),
                        charging(stop, request.vehicle()), request.reserveSocPct()));
                // An observation may restore onward state, but cannot certify an unreachable or unknown earlier leg.
                uncertainOrInfeasible |= !"FEASIBLE".equals(result.predictedLegFeasibility());
                forward = result.departureSocPct();
                previous = stop;
            }
        }
        if (previous != null && !request.stops().isEmpty() && !samePosition(previous, request.stops().getFirst())) {
            return new State(null, "The selected day starts at a different location from the preceding day. Connect the itinerary before optimizing.");
        }
        if (uncertainOrInfeasible || forward == null) {
            return new State(null, "The preceding itinerary has an unavailable or unreachable battery state. Check its route and charging stops before optimizing this day.");
        }
        return new State(forward, null);
    }

    private static RoutePointRequest point(EvRouteStopRequest stop) {
        return new RoutePointRequest(stop.itemId(), stop.name(), stop.chargerStop() ? RoutePointType.CHARGER : RoutePointType.PLACE, stop.lat(), stop.lng());
    }

    private static boolean samePosition(EvRouteStopRequest a, EvRouteStopRequest b) {
        return Math.abs(a.lat() - b.lat()) <= 1e-6 && Math.abs(a.lng() - b.lng()) <= 1e-6;
    }

    private static CanonicalEnergy.Charging charging(EvRouteStopRequest stop, EvVehicleSpec vehicle) {
        if (!stop.chargerStop()) return null;
        var station = stop.charger();
        var matching = station.connectorTypes().stream().filter(c -> c != EvConnectorType.OTHER)
                .filter(vehicle.connectorTypes()::contains).toList();
        Boolean compatible = !matching.isEmpty() ? Boolean.TRUE
                : station.connectorTypes().contains(EvConnectorType.OTHER) || vehicle.connectorTypes().contains(EvConnectorType.OTHER)
                ? null : Boolean.FALSE;
        Double limit = matching.stream().map(c -> DC.contains(c) ? vehicle.maxDcKw() : vehicle.maxAcKw())
                .filter(CanonicalEnergy::positive).max(Double::compare).orElse(null);
        Double power = limit != null && station.maxKw() > 0 ? Math.min(limit, station.maxKw()) : null;
        return new CanonicalEnergy.Charging(stop.targetBatteryPct() == null ? null : stop.targetBatteryPct().doubleValue(),
                stop.estimatedChargeMinutes() == null ? null : stop.estimatedChargeMinutes().doubleValue(), compatible, power);
    }
}
