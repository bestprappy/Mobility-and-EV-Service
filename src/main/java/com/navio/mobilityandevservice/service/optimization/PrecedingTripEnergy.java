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
    /** {@code batteryShortfall}: the itinerary is known and connected, it just needs more charge to complete. */
    record State(Double socPct, String failure, boolean batteryShortfall, Integer uncheckedDayIndex) {
        State(Double socPct, String failure, boolean batteryShortfall) { this(socPct, failure, batteryShortfall, null); }
    }
    private PrecedingTripEnergy() {}

    /** The battery the selected day starts with. Across a gap only a reading can supply it. */
    static State resolve(EvRouteOptimizationRequest request, RouteService routes) {
        return replay(request, routes, false);
    }

    /**
     * Looks ahead from the selected day's end, which only reaches as far as the battery can travel: at a
     * gap the plan has no further effect, so the replay stops and reports that day as unchecked.
     */
    static State resolveLaterDays(EvRouteOptimizationRequest request, RouteService routes) {
        return replay(request, routes, true);
    }

    private static State replay(EvRouteOptimizationRequest request, RouteService routes, boolean stopAtGap) {
        Double forward = request.startingSocPct();
        EvRouteStopRequest previous = null;
        boolean uncertainOrInfeasible = false;
        boolean belowReserve = false;
        boolean shortfall = false;
        boolean unknownFirst = false;
        boolean stoppedAtGap = false;
        Integer uncheckedDayIndex = null;
        for (int dayIndex = 0; dayIndex < request.precedingDays().size() && !stoppedAtGap; dayIndex++) {
            var day = request.precedingDays().get(dayIndex);
            boolean gap = previous != null && !day.stops().isEmpty() && !samePosition(previous, day.stops().getFirst());
            if (gap && stopAtGap) {
                stoppedAtGap = true;
                // A day with its own reading stands alone, so there is nothing left unchecked to report.
                if (day.stops().getFirst().observedSocPct() == null) uncheckedDayIndex = dayIndex;
                continue;
            }
            boolean restartsFromReading = gap && day.stops().getFirst().observedSocPct() != null;
            if (restartsFromReading) {
                // Nothing before the gap reaches this battery, including an earlier day's shortfall.
                forward = day.stops().getFirst().observedSocPct();
                uncertainOrInfeasible = belowReserve = shortfall = unknownFirst = false;
            }
            List<RouteSegmentResponse> segments = day.stops().size() < 2 ? List.of()
                    : routes.computeDirections(new DirectionsRequest(RouteProfile.DRIVING_TRAFFIC,
                    List.of(new RoutePointGroupRequest(day.blockId(), day.stops().stream()
                            .map(PrecedingTripEnergy::point).toList())))).segments();
            for (int i = 0; i < day.stops().size(); i++) {
                var stop = day.stops().get(i);
                Double km = null;
                if (i == 0) {
                    if (previous == null || samePosition(previous, stop) || restartsFromReading) km = 0.0;
                } else {
                    String from = day.stops().get(i - 1).itemId();
                    var matches = segments.stream().filter(s -> day.blockId().equals(s.blockId())
                            && from.equals(s.fromItemId()) && stop.itemId().equals(s.toItemId())).toList();
                    var leg = matches.size() == 1 ? matches.getFirst() : null;
                    if (leg != null && leg.status() == RouteSegmentStatus.ROUTED && leg.distanceMeters() != null
                            && leg.distanceMeters() >= 0 && Double.isFinite(leg.distanceMeters())) km = leg.distanceMeters() / 1000.0;
                }
                var result = CanonicalEnergy.calculate(new CanonicalEnergy.Input(request.vehicle().resolvedModel(),
                        km, km == null ? "UNAVAILABLE" : "ROUTED", forward, stop.observedSocPct(),
                        charging(stop, request.vehicle()), request.reserveSocPct()));
                // An observation may restore onward state, but cannot certify an unreachable or unknown earlier leg.
                uncertainOrInfeasible |= !"FEASIBLE".equals(result.predictedLegFeasibility())
                        || result.departureSocPct() == null || result.nominalChargeMinutes() == null;
                // Starting SoC is not an arrival. Check every actual prior driving leg before observations.
                belowReserve |= i > 0 && "BELOW_RESERVE".equals(result.predictedArrivalReserveStatus());
                // Whatever follows an unreachable leg is unknown too, so only the first problem classifies the failure.
                boolean unreachable = "INFEASIBLE".equals(result.predictedLegFeasibility());
                unknownFirst |= !shortfall && !unreachable && (!"FEASIBLE".equals(result.predictedLegFeasibility())
                        || result.departureSocPct() == null || result.nominalChargeMinutes() == null);
                shortfall |= !unknownFirst && i > 0 && (unreachable || "BELOW_RESERVE".equals(result.predictedArrivalReserveStatus()));
                forward = result.departureSocPct();
                previous = stop;
            }
        }
        if (!stoppedAtGap && previous != null && !request.stops().isEmpty() && !samePosition(previous, request.stops().getFirst())) {
            Double reading = request.stops().getFirst().observedSocPct();
            if (reading != null) return new State(reading, null, false);
            return new State(null, "The selected day starts at a different location from the preceding day, so its starting battery is unknown. Set where it starts, or record the battery level at its first stop, before optimizing.", false);
        }
        if (uncertainOrInfeasible || forward == null) {
            return new State(null, "The preceding itinerary has an unavailable or unreachable battery state. Check its route and charging stops before optimizing this day.", shortfall, uncheckedDayIndex);
        }
        if (belowReserve || (previous != null && forward + 1e-9 < request.reserveSocPct())) return new State(null, "The preceding itinerary falls below the selected arrival reserve. Review its charging plan before optimizing this day.", shortfall, uncheckedDayIndex);
        return new State(forward, null, false, uncheckedDayIndex);
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
        if (station.status() != com.navio.mobilityandevservice.domain.ev.EvChargerStatus.ACTIVE) compatible = null;
        Double limit = matching.stream().map(c -> DC.contains(c) ? vehicle.maxDcKw() : vehicle.maxAcKw())
                .filter(CanonicalEnergy::positive).max(Double::compare).orElse(null);
        Double power = limit != null && station.maxKw() > 0 ? Math.min(limit, station.maxKw()) : null;
        return new CanonicalEnergy.Charging(stop.effectiveTargetSocPct(),
                stop.effectiveChargeMinutes(), compatible, power);
    }
}
