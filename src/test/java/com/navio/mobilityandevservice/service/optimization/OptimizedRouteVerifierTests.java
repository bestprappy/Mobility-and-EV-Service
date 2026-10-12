package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.energy.CanonicalEnergy;
import com.navio.mobilityandevservice.domain.ev.*;
import com.navio.mobilityandevservice.domain.optimization.*;
import com.navio.mobilityandevservice.domain.place.PlaceLocationResponse;
import com.navio.mobilityandevservice.domain.route.*;
import com.navio.mobilityandevservice.service.RouteService;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongBiFunction;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OptimizedRouteVerifierTests {
    private final EvVehicleSpec vehicle = new EvVehicleSpec(50.0,20.0,7.0,50.0,List.of(EvConnectorType.CCS2),
            new CanonicalEnergy.Model("CONSUMPTION",20.0,50.0,null));
    private EvRouteStopRequest stop(String id, Double observed) {
        return new EvRouteStopRequest(id,id,13,100,null,false,null,null,observed);
    }
    private EvChargerResponse charger(EvConnectorType connector, double power) {
        var charger=mock(EvChargerResponse.class);
        when(charger.id()).thenReturn("station"); when(charger.name()).thenReturn("Station");
        when(charger.location()).thenReturn(new PlaceLocationResponse(13,101,"Address","station"));
        when(charger.status()).thenReturn(EvChargerStatus.ACTIVE);
        when(charger.connectorTypes()).thenReturn(List.of(connector)); when(charger.maxKw()).thenReturn(power);
        when(charger.availableConnectors()).thenReturn(null);
        return charger;
    }
    private EvRouteOptimizationRequest request(List<EvRouteStopRequest> stops, double initial) {
        return new EvRouteOptimizationRequest("day",stops,vehicle,initial,12.0,70.0,20.0);
    }
    private OptimizationRoute route(EvRouteOptimizationRequest request) {
        return new OptimizationRoute(List.of(new OptimizationRouteSpan(0,request.stops().getFirst(),request.stops().getLast(),
                20,1200,List.of(),List.of())));
    }
    private OptimizationSearchResult search(List<PlannedChargeStop> charges) {
        return new OptimizationSearchResult(true,70.0,0,0,charges,List.of());
    }
    private RouteService roads(ToLongBiFunction<String,String> distance, RouteSegmentStatus status) {
        var routes=mock(RouteService.class);
        when(routes.computeDirections(any())).thenAnswer(call -> {
            DirectionsRequest request=call.getArgument(0);
            var segments=new ArrayList<RouteSegmentResponse>();
            for(var group:request.groups()) {
                assertThat(group.points()).hasSizeLessThanOrEqualTo(25);
                for(int i=1;i<group.points().size();i++) {
                    var a=group.points().get(i-1);var b=group.points().get(i);
                    segments.add(new RouteSegmentResponse("leg"+i,group.blockId(),a.id(),b.id(),a.name(),b.name(),status,
                            null,distance.applyAsLong(a.id(),b.id()),600L,null));
                }
            }
            return new DirectionsResponse(segments);
        });
        return routes;
    }
    private PlannedChargeStop charge(EvChargerResponse charger) {
        return new PlannedChargeStop(0,1,"b",new OptimizationChargerCandidate(charger,10,1,null),60,80,12,2);
    }
    @Test void actualRoadAccessCanInvalidateAReachableCorridorCandidate() {
        var req=request(List.of(stop("a",null),stop("b",null)),20);
        var result=OptimizedRouteVerifier.verify(route(req),req,search(List.of(charge(charger(EvConnectorType.CCS2,50)))),
                roads((a,b)->a.equals("a") && b.equals("b")?210000L:110000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isFalse(); assertThat(result.chargeStops()).isEmpty();
        assertThat(result.warnings()).anyMatch(w->w.contains("unreachable"));
    }
    @Test void measuredAccessDetourMustRespectTheRequestedLimit() {
        var req=request(List.of(stop("a",null),stop("b",null)),100);
        var result=OptimizedRouteVerifier.verify(route(req),req,search(List.of(charge(charger(EvConnectorType.CCS2,50)))),
                roads((a,b)->a.equals("a") && b.equals("b")?10000L:20000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isFalse();assertThat(result.warnings()).anyMatch(w->w.contains("maximum detour"));
    }
    @Test void verifiedDetourAndUnknownAvailabilityAreReportedTruthfully() {
        var req=request(List.of(stop("a",null),stop("b",null)),80);
        var result=OptimizedRouteVerifier.verify(route(req),req,search(List.of(charge(charger(EvConnectorType.CCS2,50)))),
                roads((a,b)->a.equals("a") && b.equals("b")?35000L:20000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isTrue();assertThat(result.chargeStops().getFirst().detourKm()).isEqualTo(5);
        assertThat(result.warnings()).anyMatch(w->w.contains("availability is unknown"));
    }
    @Test void startingBelowReserveCanChargeAtTheActualOriginIncludingZeroSoc() {
        var origin=new EvRouteStopRequest("a","a",13,100,charger(EvConnectorType.CCS2,50),true,null,80);
        var req=request(List.of(origin,stop("b",null)),0);
        var route=route(req);
        var planned=new SocConstrainedRouteOptimizer().optimize(route,req);
        assertThat(planned.feasible()).isTrue();
        var result=OptimizedRouteVerifier.verify(route,req,planned,roads((a,b)->20000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isTrue();assertThat(result.finalSocPct()).isCloseTo(72,offset(1e-9));
    }
    @Test void lowStartCannotTeleportToARemoteCharger() {
        var req=request(List.of(stop("a",null),stop("b",null)),5);
        var base=route(req).spans().getFirst();
        var route=new OptimizationRoute(List.of(base.withCandidates(List.of(new OptimizationChargerCandidate(charger(EvConnectorType.CCS2,50),1,0,null)))));
        assertThat(new SocConstrainedRouteOptimizer().optimize(route,req).feasible()).isFalse();
    }
    @Test void checkpointCannotErasePredictedArrivalReserveViolation() {
        var req=request(List.of(stop("a",null),stop("b",90.0)),20);
        var result=OptimizedRouteVerifier.verify(route(req),req,search(List.of()),roads((a,b)->25000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isFalse();assertThat(result.finalSocPct()).isNull();
        assertThat(result.warnings()).anyMatch(w->w.contains("below reserve"));
    }
    @Test void lowObservedFinalStateCannotBeCertifiedAsMeetingReserve() {
        var req=request(List.of(stop("a",null),stop("b",5.0)),80);
        var result=OptimizedRouteVerifier.verify(route(req),req,search(List.of()),roads((a,b)->1000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isFalse();assertThat(result.warnings()).anyMatch(w->w.contains("final observed"));
    }
    @Test void finalChargingTargetFollowsObservationAndNeverDischarges() {
        for(int target:List.of(80,20)) {
            var end=new EvRouteStopRequest("b","b",13,101,charger(EvConnectorType.CCS2,50),false,null,target,60.0);
            var req=request(List.of(stop("a",null),end),80);
            var result=OptimizedRouteVerifier.verify(route(req),req,search(List.of()),roads((a,b)->25000L,RouteSegmentStatus.ROUTED));
            assertThat(result.feasible()).isTrue();assertThat(result.finalSocPct()).isEqualTo(Math.max(60,target));
            assertThat(result.totalChargingMinutes()).isEqualTo(target==80?12:0);
        }
    }
    @Test void finalSavedDurationIsReplayedWithFractionalSoc() {
        var end=new EvRouteStopRequest("b","b",13,101,charger(EvConnectorType.CCS2,50),true,null,null,60.0,10);
        var req=request(List.of(stop("a",null),end),80);
        var result=OptimizedRouteVerifier.verify(route(req),req,search(List.of()),roads((a,b)->25000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isTrue();assertThat(result.finalSocPct()).isCloseTo(76.6666666667,offset(1e-8));
    }
    @Test void unknownPowerConnectorOrInactiveStationCannotSupplyRequiredCharge() {
        for(var station:List.of(charger(EvConnectorType.OTHER,50),charger(EvConnectorType.TYPE2,50),charger(EvConnectorType.CCS2,0))) {
            var end=new EvRouteStopRequest("b","b",13,101,station,true,null,100);
            var req=request(List.of(stop("a",null),end),80);
            assertThat(OptimizedRouteVerifier.verify(route(req),req,search(List.of()),roads((a,b)->25000L,RouteSegmentStatus.ROUTED)).feasible()).isFalse();
        }
        var unknown=charger(EvConnectorType.OTHER,50);
        var unknownCar=new EvVehicleSpec(50.0,20.0,7.0,50.0,List.of(EvConnectorType.OTHER),vehicle.resolvedModel());
        assertThat(SocConstrainedRouteOptimizer.chargeMinutes(20,80,unknown,unknownCar)).isNull();
        var inactive=charger(EvConnectorType.CCS2,50);when(inactive.status()).thenReturn(null);
        assertThat(SocConstrainedRouteOptimizer.chargeMinutes(20,80,inactive,vehicle)).isNull();
    }
    @Test void fallbackMissingAndDuplicateRoadLegsNeverCertifyFeasibility() {
        var req=request(List.of(stop("a",null),stop("b",null)),80);
        assertThat(OptimizedRouteVerifier.verify(route(req),req,search(List.of()),roads((a,b)->1000L,RouteSegmentStatus.FALLBACK)).feasible()).isFalse();
        var missing=mock(RouteService.class);when(missing.computeDirections(any())).thenReturn(new DirectionsResponse(List.of()));
        assertThat(OptimizedRouteVerifier.verify(route(req),req,search(List.of()),missing).feasible()).isFalse();
        var duplicate=mock(RouteService.class);
        var leg=new RouteSegmentResponse("x","day","a","b","a","b",RouteSegmentStatus.ROUTED,null,1000L,60L,null);
        when(duplicate.computeDirections(any())).thenReturn(new DirectionsResponse(List.of(leg,leg)));
        assertThat(OptimizedRouteVerifier.verify(route(req),req,search(List.of()),duplicate).feasible()).isFalse();
    }

    @Test void replaySplitsLongVerifiedItinerariesWithoutOmittingBoundaryLegs() {
        var stops=new ArrayList<EvRouteStopRequest>();
        for(int i=0;i<51;i++) stops.add(stop("s"+i,null));
        var req=request(stops,80);
        var spans=new ArrayList<OptimizationRouteSpan>();
        for(int i=1;i<stops.size();i++) spans.add(new OptimizationRouteSpan(i-1,stops.get(i-1),stops.get(i),1,600,List.of(),List.of()));
        var result=OptimizedRouteVerifier.verify(new OptimizationRoute(spans),req,search(List.of()),roads((a,b)->1000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isTrue();assertThat(result.finalSocPct()).isCloseTo(60,offset(1e-8));
        assertThat(result.totalDrivingSeconds()).isEqualTo(50*600);
    }

    @Test void finalChargerWithoutTargetOrDurationDoesNotBecomeConfirmedZeroCharging() {
        var end=new EvRouteStopRequest("b","b",13,101,charger(EvConnectorType.CCS2,50),true,null);
        var req=request(List.of(stop("a",null),end),80);
        var result=OptimizedRouteVerifier.verify(route(req),req,search(List.of()),roads((a,b)->1000L,RouteSegmentStatus.ROUTED));
        assertThat(result.feasible()).isFalse();assertThat(result.finalSocPct()).isNull();
    }
}
