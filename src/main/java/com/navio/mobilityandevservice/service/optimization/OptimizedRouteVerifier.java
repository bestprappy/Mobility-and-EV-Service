package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.route.*;
import com.navio.mobilityandevservice.service.RouteService;
import com.navio.mobilityandevservice.service.simulation.EvSimulationModel;

import java.util.ArrayList;
import java.util.List;

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
        var response = routes.computeDirections(new DirectionsRequest(RouteProfile.DRIVING_TRAFFIC,
                List.of(new RoutePointGroupRequest(request.blockId(), stops.stream().map(Stop::point).toList()))));
        double soc = request.startingSocPct();
        long seconds = 0;
        int minutes = 0;
        List<PlannedChargeStop> verified = new ArrayList<>();
        for (int i = 0; i < stops.size(); i++) {
            Stop stop = stops.get(i);
            if (i > 0) {
                String from = stops.get(i - 1).point().id();
                var leg = response.segments().stream().filter(s -> s.blockId().equals(request.blockId())
                        && s.fromItemId().equals(from) && s.toItemId().equals(stop.point().id())).findFirst().orElse(null);
                if (leg == null || leg.status() != RouteSegmentStatus.ROUTED || leg.distanceMeters() == null
                        || leg.durationSeconds() == null || leg.distanceMeters() < 0 || leg.durationSeconds() < 0) {
                    return failure("The selected charging itinerary could not be verified on road routes. Try again when routing is available.");
                }
                soc -= EvSimulationModel.planningEnergy(leg.distanceMeters() / 1000.0,
                        request.vehicle().consumptionKwhPer100km()) / request.vehicle().batteryKwh() * 100;
                seconds += leg.durationSeconds();
            }
            if (soc + 1e-9 < request.reserveSocPct()) {
                return failure("The selected charging itinerary falls below reserve on its actual road route. No changes were applied; try another charger or a higher starting SOC.");
            }
            if (stop.charge() != null) {
                var charge = stop.charge();
                double departure = Math.max(soc, charge.departureSocPct());
                int charging = EvSimulationModel.chargeMinutes(soc, departure, charge.candidate().charger(), request.vehicle());
                if (departure > soc && charging == 0) return failure("The selected charger has no confirmed usable charging power.");
                verified.add(new PlannedChargeStop(charge.spanIndex(), charge.sequence(), charge.beforeItemId(), charge.candidate(),
                        (int) Math.round(soc), (int) Math.round(departure), charging, charge.detourKm()));
                soc = departure;
                minutes += charging;
            }
        }
        List<String> warnings = new ArrayList<>(search.warnings());
        warnings.add("Simulation " + EvSimulationModel.POLICY.version() + ": distance-based energy with a 12% planning margin; capacity is assumed usable. Charging uses assumed losses and a generic DC taper.");
        warnings.add("The selected stops were checked on road routes. Search uses a limited charger corridor and 5% charging targets; a global minimum travel time is not guaranteed. Allow 5 extra minutes per charging stop to connect.");
        return new OptimizationSearchResult(true, (int) Math.round(soc), seconds, minutes, verified, warnings);
    }

    private static OptimizationSearchResult failure(String message) {
        return new OptimizationSearchResult(false, 0, 0, 0, List.of(), List.of(message));
    }
}
