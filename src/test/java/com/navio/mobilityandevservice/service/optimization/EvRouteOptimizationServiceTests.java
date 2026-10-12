package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvChargerStatus;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.domain.optimization.EvChargerSelectionSource;
import com.navio.mobilityandevservice.domain.optimization.EvPlanOperation;
import com.navio.mobilityandevservice.domain.optimization.EvPlanOperationType;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationResponse;
import com.navio.mobilityandevservice.domain.optimization.EvRouteStopRequest;
import com.navio.mobilityandevservice.domain.optimization.EvVehicleSpec;
import com.navio.mobilityandevservice.domain.energy.CanonicalEnergy;
import com.navio.mobilityandevservice.domain.place.OpeningHoursResponse;
import com.navio.mobilityandevservice.domain.place.PlaceLocationResponse;
import com.navio.mobilityandevservice.domain.route.DirectionsResponse;
import com.navio.mobilityandevservice.domain.route.DirectionsRequest;
import java.util.ArrayList;
import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import com.navio.mobilityandevservice.domain.route.RouteLineString;
import com.navio.mobilityandevservice.domain.route.RouteSegmentResponse;
import com.navio.mobilityandevservice.domain.route.RouteSegmentStatus;
import com.navio.mobilityandevservice.service.RouteService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvRouteOptimizationServiceTests {

    @Test
    void followingDaysAreReplayedAndUnsafePlansHaveNoApplicableOperations() {
        var routes = mock(RouteService.class);
        var finder = mock(RouteCorridorChargerFinder.class);
        when(finder.populateCandidates(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        when(routes.computeDirections(any())).thenAnswer(call -> {
            DirectionsRequest request = call.getArgument(0);
            var group = request.groups().getFirst();
            var legs = new ArrayList<RouteSegmentResponse>();
            for (int i = 1; i < group.points().size(); i++) {
                var from = group.points().get(i - 1); var to = group.points().get(i);
                long meters = to.id().equals("far") ? 300_000 : 30_000;
                legs.add(new RouteSegmentResponse("leg-" + i, group.blockId(), from.id(), to.id(), from.name(), to.name(),
                        RouteSegmentStatus.ROUTED, null, meters, 1800L, null));
            }
            return new DirectionsResponse(legs);
        });
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var service = new EvRouteOptimizationService(routes, finder, new SocConstrainedRouteOptimizer());
        for (String id : List.of("near", "far", "observed-low", "disconnected")) {
            var start = new EvRouteStopRequest("next-start", "Next start", 13,
                    id.equals("disconnected") ? 105 : 103, null, false, null);
            var end = new EvRouteStopRequest(id, id, 13, 104, null, false, null, null,
                    id.equals("observed-low") ? 5.0 : null);
            var request = new EvRouteOptimizationRequest(base.blockId(), base.stops(), base.vehicle(),
                    base.startingSocPct(), base.reserveSocPct(), base.targetSocPct(), base.maximumDetourKm(), List.of(),
                    List.of(new EvRouteOptimizationRequest.Day("day-2", List.of(start, end))));
            var result = service.optimize(request);
            assertThat(result.feasible()).as(id).isEqualTo(id.equals("near"));
            if (!result.feasible()) assertThat(result.operations()).isEmpty();
        }
    }

    @Test
    void protectsChargerDayBoundariesWithoutMutatingSavedLockSettings() {
        var routes = mock(RouteService.class);
        var finder = mock(RouteCorridorChargerFinder.class);
        var optimizer = mock(SocConstrainedRouteOptimizer.class);
        var first = new EvRouteStopRequest("first", "First", 13, 100,
                charger("first",13,100,50,.9,false), false, EvChargerSelectionSource.MANUAL);
        var last = new EvRouteStopRequest("last", "Last", 13, 103,
                charger("last",13,103,50,.9,false), false, EvChargerSelectionSource.MANUAL);
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var request = new EvRouteOptimizationRequest("day-1", List.of(first,last), base.vehicle(),80.0,10.0,70.0,20.0);
        stubRoadRoutes(routes);
        when(finder.populateCandidates(any(),any(),any())).thenAnswer(call -> call.getArgument(0));
        when(optimizer.optimize(any(),any())).thenAnswer(call -> {
            EvRouteOptimizationRequest effective = call.getArgument(1);
            assertThat(effective.stops().getFirst().effectiveLocked()).isTrue();
            assertThat(effective.stops().getLast().effectiveLocked()).isTrue();
            return new OptimizationSearchResult(false,null,0,0,List.of(),List.of());
        });
        new EvRouteOptimizationService(routes,finder,optimizer).optimize(request);
        org.mockito.Mockito.verify(optimizer).optimize(any(),any());
        assertThat(first.effectiveLocked()).isFalse();
        assertThat(last.effectiveLocked()).isFalse();
    }

    @Test
    void replacesAnUnlockedChargerWhenTheSearchSelectsABetterCandidate() {
        RouteService routeService = mock(RouteService.class);
        RouteCorridorChargerFinder chargerFinder = mock(RouteCorridorChargerFinder.class);
        SocConstrainedRouteOptimizer optimizer = new SocConstrainedRouteOptimizer();
        EvRouteOptimizationService service = new EvRouteOptimizationService(
                routeService,
                chargerFinder,
                optimizer
        );
        EvRouteOptimizationRequest request = requestWithExistingCharger();
        stubRoadRoutes(routeService);
        when(chargerFinder.populateCandidates(any(), any(), any())).thenReturn(new OptimizationRoute(List.of(
                new OptimizationRouteSpan(
                        0,
                        request.stops().getFirst(),
                        request.stops().getLast(),
                        300,
                        14_400,
                        List.of(new RouteCoordinate(100, 13), new RouteCoordinate(103, 13)),
                        List.of(new OptimizationChargerCandidate(
                                charger("better", 13, 101.4, 180, 0.95, false),
                                140,
                                1,
                                null
                        ))
                )
        )));

        var response = service.optimize(request);

        assertThat(response.feasible()).isTrue();
        assertThat(response.operations()).hasSize(1);
        assertThat(response.operations().getFirst().type()).isEqualTo(EvPlanOperationType.REPLACE_CHARGER);
        assertThat(response.operations().getFirst().oldItemId()).isEqualTo("bad-item");
        assertThat(response.operations().getFirst().charger().id()).isEqualTo("better");
        assertThat(response.operations().getFirst().arrivalSocPct()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void chargesAtAcSpeedWhenTheVehicleCannotUseTheStationsDcConnector() {
        EvRouteOptimizationRequest request = requestWithVehicleConnectors(EvConnectorType.TYPE2);
        // 180 kW station, but the only shared connector is Type 2, so the car is limited to 11 kW AC.
        EvChargerResponse dualStandardCharger = charger(
                "dual",
                13,
                101.4,
                180,
                0.95,
                false,
                List.of(EvConnectorType.CCS2, EvConnectorType.TYPE2)
        );

        var response = optimize(request, dualStandardCharger);

        assertThat(response.feasible()).isTrue();
        assertThat(response.operations()).hasSize(1);
        double acMinutes = response.operations().getFirst().estimatedChargeMinutes();
        assertThat(acMinutes).isGreaterThan(60);

        var dcResponse = optimize(
                requestWithVehicleConnectors(EvConnectorType.CCS2, EvConnectorType.TYPE2),
                dualStandardCharger
        );
        assertThat(dcResponse.operations().getFirst().estimatedChargeMinutes()).isLessThan(acMinutes);
    }

    @Test
    void keepsAnExistingChargerThePlanMovesToAnotherSpan() {
        // The charger currently sits between the origin and the midpoint, but the corridor search
        // projects it onto the second span. Deciding retention per span would remove it in span 0
        // while span 1 still plans to charge there, and the applier would delete it for good.
        EvChargerResponse existing = charger("saved", 13, 102, 150, 0.9, false);
        EvRouteOptimizationRequest request = new EvRouteOptimizationRequest(
                "day-1",
                List.of(
                        new EvRouteStopRequest("origin", "Origin", 13, 100, null, false, null),
                        new EvRouteStopRequest(
                                "saved-item",
                                "Saved charger",
                                13,
                                102,
                                existing,
                                false,
                                EvChargerSelectionSource.MANUAL
                        ),
                        new EvRouteStopRequest("midpoint", "Midpoint", 13, 101, null, false, null),
                        new EvRouteStopRequest("destination", "Destination", 13, 103, null, false, null)
                ),
                new EvVehicleSpec(60.0, 20.0, 11.0, 180.0, List.of(EvConnectorType.CCS2), new CanonicalEnergy.Model("CONSUMPTION", 20.0, 60.0, null)),
                80.0,
                10.0,
                70.0,
                20.0
        );

        RouteService routeService = mock(RouteService.class);
        RouteCorridorChargerFinder chargerFinder = mock(RouteCorridorChargerFinder.class);
        EvRouteOptimizationService service = new EvRouteOptimizationService(
                routeService,
                chargerFinder,
                new SocConstrainedRouteOptimizer()
        );
        stubRoadRoutes(routeService);
        when(chargerFinder.populateCandidates(any(), any(), any())).thenReturn(new OptimizationRoute(List.of(
                span(0, request.stops().get(0), request.stops().get(2), 30, 1_800, List.of()),
                span(
                        1,
                        request.stops().get(2),
                        request.stops().get(3),
                        300,
                        14_400,
                        List.of(candidate(existing, 140, 1, "saved-item"))
                )
        )));

        var response = service.optimize(request);

        assertThat(response.feasible()).isTrue();
        assertThat(response.operations())
                .extracting(EvPlanOperation::type)
                .doesNotContain(EvPlanOperationType.REMOVE_CHARGER, EvPlanOperationType.REPLACE_CHARGER);
        assertThat(response.operations())
                .extracting(EvPlanOperation::type)
                .containsExactly(EvPlanOperationType.UPDATE_CHARGER);
        assertThat(response.operations().getFirst().oldItemId()).isEqualTo("saved-item");
    }

    @Test
    void honorsAnExplicitFullBatteryTargetAndKeepsTheStation() {
        EvChargerResponse station = charger("target-station", 13, 101, 150, 0.9, false);
        EvRouteStopRequest stop = new EvRouteStopRequest("target-item", "Target station", 13, 101, station, false, EvChargerSelectionSource.MANUAL, 100);
        assertThat(stop.effectiveLocked()).isTrue();
        var request = new EvRouteOptimizationRequest("day-1", List.of(
                new EvRouteStopRequest("origin", "Origin", 13, 100, null, false, null), stop,
                new EvRouteStopRequest("destination", "Destination", 13, 103, null, false, null)),
                new EvVehicleSpec(60.0, 20.0, 11.0, 180.0, List.of(EvConnectorType.CCS2), new CanonicalEnergy.Model("CONSUMPTION", 20.0, 60.0, null)),
                80.0, 10.0, 70.0, 20.0);
        var response = optimize(request, candidate(station, 140, 1, "target-item"));
        assertThat(response.feasible()).isTrue();
        assertThat(response.operations()).extracting(EvPlanOperation::type)
                .doesNotContain(EvPlanOperationType.REMOVE_CHARGER, EvPlanOperationType.REPLACE_CHARGER);
        assertThat(response.operations()).anySatisfy(operation -> {
            assertThat(operation.oldItemId()).isEqualTo("target-item");
                    assertThat(operation.departureSocPct()).isEqualTo(100);
        });
    }

    private void stubRoadRoutes(RouteService service) {
        when(service.computeDirections(any())).thenAnswer(invocation -> {
            DirectionsRequest request = invocation.getArgument(0);
            var group = request.groups().getFirst();
            var legs = new ArrayList<RouteSegmentResponse>();
            for (int i = 1; i < group.points().size(); i++) {
                var from = group.points().get(i - 1);
                var to = group.points().get(i);
                long km = to.id().equals("midpoint") ? 30 : to.id().startsWith("ev-verify-") ? 140
                        : from.id().startsWith("ev-verify-") ? 160 : 300;
                legs.add(new RouteSegmentResponse("leg-" + i, group.blockId(), from.id(), to.id(), from.name(), to.name(),
                        RouteSegmentStatus.ROUTED, null, km * 1000, km * 60, null));
            }
            return new DirectionsResponse(legs);
        });
    }

    private OptimizationRouteSpan span(
            int index,
            EvRouteStopRequest from,
            EvRouteStopRequest to,
            double distanceKm,
            long durationSeconds,
            List<OptimizationChargerCandidate> candidates
    ) {
        return new OptimizationRouteSpan(
                index,
                from,
                to,
                distanceKm,
                durationSeconds,
                List.of(new RouteCoordinate(from.lng(), from.lat()), new RouteCoordinate(to.lng(), to.lat())),
                candidates
        );
    }

    private EvRouteOptimizationResponse optimize(
            EvRouteOptimizationRequest request,
            EvChargerResponse candidateCharger
    ) {
        return optimize(request, candidate(candidateCharger, 140, 1, null));
    }

    private EvRouteOptimizationResponse optimize(
            EvRouteOptimizationRequest request,
            OptimizationChargerCandidate candidate
    ) {
        RouteService routeService = mock(RouteService.class);
        RouteCorridorChargerFinder chargerFinder = mock(RouteCorridorChargerFinder.class);
        EvRouteOptimizationService service = new EvRouteOptimizationService(
                routeService,
                chargerFinder,
                new SocConstrainedRouteOptimizer()
        );
        stubRoadRoutes(routeService);
        when(chargerFinder.populateCandidates(any(), any(), any())).thenReturn(new OptimizationRoute(List.of(
                new OptimizationRouteSpan(
                        0,
                        request.stops().getFirst(),
                        request.stops().getLast(),
                        300,
                        14_400,
                        List.of(new RouteCoordinate(100, 13), new RouteCoordinate(103, 13)),
                        List.of(candidate)
                )
        )));
        return service.optimize(request);
    }

    private OptimizationChargerCandidate candidate(
            EvChargerResponse charger,
            double progressKm,
            double deviationKm,
            String existingItemId
    ) {
        return new OptimizationChargerCandidate(charger, progressKm, deviationKm, existingItemId);
    }

    @org.junit.jupiter.api.Test
    void continuousDepletionMatchesAnUnsplitRouteWithoutSafetyMultiplier() {
        var request = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var spans = new java.util.ArrayList<OptimizationRouteSpan>();
        for (int i=0;i<1000;i++) spans.add(new OptimizationRouteSpan(i, request.stops().getFirst(), request.stops().getLast(), .09, 1, List.of(), List.of()));
        var optimizer = new SocConstrainedRouteOptimizer();
        var split = optimizer.optimize(new OptimizationRoute(spans), request);
        var whole = optimizer.optimize(new OptimizationRoute(List.of(new OptimizationRouteSpan(0, request.stops().getFirst(), request.stops().getLast(), 90, 1000, List.of(), List.of()))), request);
        assertThat(split.feasible()).isTrue();
        assertThat(split.finalSocPct()).isCloseTo(50.0, org.assertj.core.data.Offset.offset(1e-8));
        assertThat(split.finalSocPct()).isCloseTo(whole.finalSocPct(), org.assertj.core.data.Offset.offset(1e-8));
        var fractional = new EvRouteOptimizationRequest(request.blockId(), request.stops(), request.vehicle(), 80.125, 10.0, 70.0, 20.0);
        assertThat(optimizer.optimize(new OptimizationRoute(List.of(new OptimizationRouteSpan(0, request.stops().getFirst(), request.stops().getLast(), 0, 0, List.of(), List.of()))), fractional).finalSocPct()).isEqualTo(80.125);
    }

    @org.junit.jupiter.api.Test
    void legacyUnknownCapacityDoesNotBecomeUsableOrZero() {
        var base=requestWithVehicleConnectors(EvConnectorType.CCS2);
        var request=new EvRouteOptimizationRequest(base.blockId(),base.stops(),new EvVehicleSpec(60.0,20.0,11.0,180.0,List.of(EvConnectorType.CCS2)),80.0,10.0,70.0,20.0);
        var result=new SocConstrainedRouteOptimizer().optimize(new OptimizationRoute(List.of(new OptimizationRouteSpan(0,request.stops().getFirst(),request.stops().getLast(),10,600,List.of(),List.of()))),request);
        assertThat(result.feasible()).isFalse();
        assertThat(result.finalSocPct()).isNull();
        assertThat(result.warnings()).anyMatch(w -> w.contains("unavailable"));
    }

    @org.junit.jupiter.api.Test
    void denseContinuousSearchIsBoundedAndNeverClaimsSuccessWhenCapped() {
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(5), () -> {
            var request=requestWithVehicleConnectors(EvConnectorType.CCS2);
            var candidates=new java.util.ArrayList<OptimizationChargerCandidate>();
            for(int i=1;i<=80;i++) candidates.add(candidate(charger("dense-"+i,13,100+i*.01,50+i,.9,false),i*3,.001*i,null));
            var result=new SocConstrainedRouteOptimizer().optimize(new OptimizationRoute(List.of(new OptimizationRouteSpan(0,request.stops().getFirst(),request.stops().getLast(),300,18000,List.of(),candidates))),request);
            if(result.feasible()) assertThat(result.finalSocPct()).isGreaterThanOrEqualTo(10);
            else { assertThat(result.finalSocPct()).isNull(); assertThat(result.warnings()).anyMatch(w -> w.contains("search limit")); }
        });
    }

    private EvRouteOptimizationRequest requestWithVehicleConnectors(EvConnectorType... connectors) {
        return new EvRouteOptimizationRequest(
                "day-1",
                List.of(
                        new EvRouteStopRequest("origin", "Origin", 13, 100, null, false, null),
                        new EvRouteStopRequest("destination", "Destination", 13, 103, null, false, null)
                ),
                new EvVehicleSpec(60.0, 20.0, 11.0, 180.0, List.of(connectors), new CanonicalEnergy.Model("CONSUMPTION", 20.0, 60.0, null)),
                80.0,
                10.0,
                70.0,
                20.0
        );
    }

    private EvRouteOptimizationRequest requestWithExistingCharger() {
        return new EvRouteOptimizationRequest(
                "day-1",
                List.of(
                        new EvRouteStopRequest("origin", "Origin", 13, 100, null, false, null),
                        new EvRouteStopRequest(
                                "bad-item",
                                "Bad charger",
                                13,
                                101.5,
                                charger("bad", 13, 101.5, 50, 0.2, true),
                                false,
                                EvChargerSelectionSource.MANUAL
                        ),
                        new EvRouteStopRequest("destination", "Destination", 13, 103, null, false, null)
                ),
                new EvVehicleSpec(60.0, 20.0, 11.0, 180.0, List.of(EvConnectorType.CCS2), new CanonicalEnergy.Model("CONSUMPTION", 20.0, 60.0, null)),
                80.0,
                10.0,
                70.0,
                20.0
        );
    }

    private EvChargerResponse charger(
            String id,
            double lat,
            double lng,
            double maxKw,
            double confidence,
            boolean stale
    ) {
        return charger(id, lat, lng, maxKw, confidence, stale, List.of(EvConnectorType.CCS2));
    }

    private EvChargerResponse charger(
            String id,
            double lat,
            double lng,
            double maxKw,
            double confidence,
            boolean stale,
            List<EvConnectorType> connectorTypes
    ) {
        return new EvChargerResponse(
                id,
                id,
                "Operator",
                new PlaceLocationResponse(lat, lng, "Address", id),
                "Address",
                "Province",
                connectorTypes,
                maxKw,
                4,
                2,
                null,
                OpeningHoursResponse.empty(),
                "TEST",
                stale ? "STALE" : "VERIFIED",
                EvChargerStatus.ACTIVE,
                4.5,
                10,
                confidence,
                stale
        );
    }
}
