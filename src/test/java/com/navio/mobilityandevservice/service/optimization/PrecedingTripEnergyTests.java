package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.energy.CanonicalEnergy;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.optimization.*;
import com.navio.mobilityandevservice.domain.route.*;
import com.navio.mobilityandevservice.service.RouteService;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PrecedingTripEnergyTests {
    private final EvVehicleSpec vehicle = new EvVehicleSpec(50.0, 20.0, 7.0, 50.0, List.of(EvConnectorType.CCS2),
            new CanonicalEnergy.Model("CONSUMPTION", 20.0, 50.0, null));
    private EvRouteStopRequest stop(String id, double lng, Double observation) {
        return new EvRouteStopRequest(id, id, 13, lng, null, false, null, null, observation);
    }
    private EvRouteOptimizationRequest request(List<EvRouteOptimizationRequest.Day> days) {
        return new EvRouteOptimizationRequest("day-3", List.of(stop("e", 102, null), stop("f", 103, null)),
                vehicle, 100.0, 12.0, 70.0, 20.0, days);
    }
    private RouteService routes(long meters, RouteSegmentStatus status) {
        RouteService routes = mock(RouteService.class);
        when(routes.computeDirections(any())).thenAnswer(call -> {
            DirectionsRequest request = call.getArgument(0);
            var group = request.groups().getFirst();
            var points = group.points();
            var legs = new java.util.ArrayList<RouteSegmentResponse>();
            for (int i = 1; i < points.size(); i++) legs.add(new RouteSegmentResponse("leg" + i, group.blockId(),
                    points.get(i-1).id(), points.get(i).id(), "from", "to", status, null, meters, 600L, null));
            return new DirectionsResponse(legs);
        });
        return routes;
    }
    @Test void carriesBatteryAcrossDaysAndAppliesObservationsChronologically() {
        var request = request(List.of(new EvRouteOptimizationRequest.Day("day-1", List.of(stop("a",100,null), stop("b",101,68.0))),
                new EvRouteOptimizationRequest.Day("day-2", List.of(stop("c",101,null), stop("d",102,null)))));
        var state = PrecedingTripEnergy.resolve(request, routes(25000, RouteSegmentStatus.ROUTED));
        assertThat(state.failure()).isNull();
        assertThat(state.socPct()).isEqualTo(58);
    }
    @Test void removingAnObservationRestoresPredictedPropagation() {
        var request = request(List.of(new EvRouteOptimizationRequest.Day("day-1", List.of(stop("a",100,null), stop("b",101,null))),
                new EvRouteOptimizationRequest.Day("day-2", List.of(stop("c",101,null), stop("d",102,null)))));
        assertThat(PrecedingTripEnergy.resolve(request, routes(25000, RouteSegmentStatus.ROUTED)).socPct()).isEqualTo(80);
    }
    @Test void observationDoesNotEraseAnUnreachableEarlierLeg() {
        var request = request(List.of(new EvRouteOptimizationRequest.Day("day-1", List.of(stop("a",100,null), stop("b",102,80.0)))));
        var result = PrecedingTripEnergy.resolve(request, routes(300000, RouteSegmentStatus.ROUTED));
        assertThat(result.socPct()).isNull();
        assertThat(result.failure()).contains("unreachable");
    }
    @Test void fallbackRoutesCannotCertifyPriorDayState() {
        var request = request(List.of(new EvRouteOptimizationRequest.Day("day-1", List.of(stop("a",100,null), stop("b",102,null)))));
        assertThat(PrecedingTripEnergy.resolve(request, routes(10000, RouteSegmentStatus.FALLBACK)).socPct()).isNull();
    }
    @Test void disconnectedDaysAreNotTreatedAsZeroDistance() {
        var request = request(List.of(new EvRouteOptimizationRequest.Day("day-1", List.of(stop("a",100,null), stop("b",101,null)))));
        assertThat(PrecedingTripEnergy.resolve(request, routes(10000, RouteSegmentStatus.ROUTED)).failure()).contains("different location");
    }
    @Test void firstDayUsesTripStartingBatteryWithoutFetchingEarlierRoutes() {
        var routes = mock(RouteService.class);
        assertThat(PrecedingTripEnergy.resolve(request(List.of()), routes).socPct()).isEqualTo(100);
        verifyNoInteractions(routes);
    }

    @Test void anObservationCannotHideAPriorReserveViolation() {
        var base=request(List.of(new EvRouteOptimizationRequest.Day("day-1",List.of(stop("a",100,null),stop("b",102,80.0)))));
        var low=new EvRouteOptimizationRequest(base.blockId(),base.stops(),vehicle,15.0,12.0,70.0,20.0,base.precedingDays());
        assertThat(PrecedingTripEnergy.resolve(low,routes(25000,RouteSegmentStatus.ROUTED)).failure()).contains("reserve");
    }

    private EvRouteOptimizationRequest withCharge(EvConnectorType connector, Integer target, Integer minutes) {
        var station = mock(EvChargerResponse.class);
        when(station.status()).thenReturn(com.navio.mobilityandevservice.domain.ev.EvChargerStatus.ACTIVE);
        when(station.connectorTypes()).thenReturn(List.of(connector));
        when(station.maxKw()).thenReturn(50.0);
        var charge = new EvRouteStopRequest("charge", "charge", 13, 101, station, false, null, target, 60.0, minutes);
        return request(List.of(new EvRouteOptimizationRequest.Day("day-1", List.of(stop("a",100,null), charge, stop("b",102,null)))));
    }
    @Test void savedTargetChargingIsAppliedAfterObservationWithoutChangingEarlierDriving() {
        var result = PrecedingTripEnergy.resolve(withCharge(EvConnectorType.CCS2,80,null), routes(25000,RouteSegmentStatus.ROUTED));
        assertThat(result.failure()).isNull();
        assertThat(result.socPct()).isEqualTo(70);
    }
    @Test void savedDurationChargingRetainsFractionalBatteryState() {
        var result = PrecedingTripEnergy.resolve(withCharge(EvConnectorType.CCS2,null,10), routes(25000,RouteSegmentStatus.ROUTED));
        assertThat(result.socPct()).isCloseTo(66.66666666666667, org.assertj.core.data.Offset.offset(1e-9));
    }
    @Test void unknownConnectorDoesNotBecomeCompatibleAndKnownMismatchAddsNoCharge() {
        assertThat(PrecedingTripEnergy.resolve(withCharge(EvConnectorType.OTHER,80,null), routes(25000,RouteSegmentStatus.ROUTED)).socPct()).isNull();
        assertThat(PrecedingTripEnergy.resolve(withCharge(EvConnectorType.TYPE2,80,null), routes(25000,RouteSegmentStatus.ROUTED)).socPct()).isEqualTo(50);
    }
    @Test void multipleCheckpointsReplaceOnlyTheirOnwardState() {
        var request = request(List.of(new EvRouteOptimizationRequest.Day("day-1", List.of(stop("a",100,null), stop("b",101,68.0))),
                new EvRouteOptimizationRequest.Day("day-2", List.of(stop("c",101,null), stop("d",102,35.0)))));
        assertThat(PrecedingTripEnergy.resolve(request, routes(25000,RouteSegmentStatus.ROUTED)).socPct()).isEqualTo(35);
    }
}
