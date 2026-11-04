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
    void corridorRetainsChargersNearBothEndpointsButRejectsExcessiveDetours() {
        var service = mock(com.navio.mobilityandevservice.service.EvChargerService.class);
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var stations = List.of(
                charger("at-origin", 13, 100, 50, .9, false),
                charger("near-origin", 13, 100.015, 50, .9, false),
                charger("near-destination", 13, 102.985, 50, .9, false),
                charger("at-destination", 13, 103, 50, .9, false),
                charger("far-off-route", 14, 101, 50, .9, false));
        when(service.nearby(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.navio.mobilityandevservice.domain.ev.EvChargerListResponse(stations, null));
        var route = new OptimizationRoute(List.of(new OptimizationRouteSpan(0,
                base.stops().getFirst(), base.stops().getLast(), 300, 14400,
                List.of(new RouteCoordinate(100, 13), new RouteCoordinate(103, 13)), List.of())));
        var result = new RouteCorridorChargerFinder(service).populateCandidates(route, base, List.of());
        assertThat(result.spans().getFirst().candidates()).extracting(candidate -> candidate.charger().id())
                .containsExactly("at-origin", "near-origin", "near-destination", "at-destination");
    }

    @Test
    void automaticStopsRespectTheRequestedMinimumChargeTarget() {
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var candidate = candidate(charger("target", 13, 101, 180, .9, false), 30, 0, null);
        var route = new OptimizationRoute(List.of(span(0, base.stops().getFirst(), base.stops().getLast(),
                90, 3600, List.of(candidate))));
        for (double target : List.of(70.0, 95.0)) {
            var request = new EvRouteOptimizationRequest(base.blockId(), base.stops(), base.vehicle(),
                    30.0, 10.0, target, 20.0);
            var result = new SocConstrainedRouteOptimizer().optimize(route, request);
            assertThat(result.feasible()).isTrue();
            assertThat(result.chargeStops()).hasSize(1);
            assertThat(result.chargeStops().getFirst().departureSocPct()).isGreaterThanOrEqualTo(target);
        }
    }

    @Test
    void chargingAtAnUnlockedDayOriginUpdatesItWithoutRelocatingIt() {
        var routes = mock(RouteService.class);
        var finder = mock(RouteCorridorChargerFinder.class);
        stubRoadRoutes(routes);
        when(finder.populateCandidates(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var origin = new EvRouteStopRequest("origin-charger", "Origin charger", 13, 100,
                charger("origin-station", 13, 100, 180, .9, false), false, EvChargerSelectionSource.MANUAL);
        var end = new EvRouteStopRequest("midpoint", "Destination", 13, 101, null, false, null);
        var request = new EvRouteOptimizationRequest("day-1", List.of(origin, end),
                base.vehicle(), 10.0, 10.0, 70.0, 20.0);
        var result = new EvRouteOptimizationService(routes, finder, new SocConstrainedRouteOptimizer()).optimize(request);
        assertThat(result.feasible()).isTrue();
        assertThat(result.operations()).hasSize(1);
        assertThat(result.operations().getFirst().oldItemId()).isEqualTo("origin-charger");
        assertThat(result.operations().getFirst().beforeItemId()).isNull();
    }

    @Test
    void returnTripCanChargeAtTwoSeparateVisitsToTheSameLockedStation() {
        var station = charger("same-station", 13, 100, 180, .9, false);
        var first = new EvRouteStopRequest("visit-1", "First visit", 13, 100, station, true, null, 80);
        var middle = new EvRouteStopRequest("outbound", "Out and back", 13, 101, null, false, null);
        var second = new EvRouteStopRequest("visit-2", "Return visit", 13, 100, station, true, null, 80);
        var end = new EvRouteStopRequest("end", "End", 13, 102, null, false, null);
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var request = new EvRouteOptimizationRequest("day-1", List.of(first, middle, second, end),
                base.vehicle(), 20.0, 10.0, 70.0, 20.0);
        var route = new OptimizationRoute(List.of(
                span(0, first, middle, 90, 3600, List.of()),
                span(1, middle, second, 90, 3600, List.of()),
                span(2, second, end, 150, 6000, List.of())));
        var result = new SocConstrainedRouteOptimizer().optimize(route, request);
        assertThat(result.feasible()).isTrue();
        assertThat(result.chargeStops()).extracting(stop -> stop.candidate().existingItemId())
                .containsExactly("visit-1", "visit-2");
        assertThat(result.finalSocPct()).isCloseTo(30, org.assertj.core.data.Offset.offset(1e-9));
    }

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
            // "far" only needs its own charger, which that day can plan next, so it warns rather than blocks.
            // "disconnected" changed on purpose from blocking: no battery can cross the gap, so the block
            // protected nothing. It warns that the day was not checked instead of implying it was.
            assertThat(result.feasible()).as(id).isEqualTo(!id.equals("observed-low"));
            assertThat(result.warnings().stream().anyMatch(warning -> warning.contains("later day"))).as(id).isEqualTo(id.equals("far") || id.equals("observed-low"));
            assertThat(result.warnings().stream().anyMatch(warning -> warning.contains("wasn't checked"))).as(id).isEqualTo(id.equals("disconnected"));
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

    @Test
    void planningOneDayIsNotBlockedByALaterDayThatStillNeedsItsOwnCharger() {
        // Each day needs a charger. Refusing day 1 because day 2 has none yet, while day 2 refuses
        // because day 1 has none yet, would leave the traveller unable to plan either day.
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var nextStart = new EvRouteStopRequest("next-start", "Next start", 13, 103, null, false, null);
        var far = new EvRouteStopRequest("far", "Far", 13, 104, null, false, null);
        var request = new EvRouteOptimizationRequest(base.blockId(), base.stops(), base.vehicle(), 80.0, 10.0, 70.0, 20.0,
                List.of(), List.of(new EvRouteOptimizationRequest.Day("day-2", List.of(nextStart, far))));

        var response = optimize(request, charger("mid", 13, 101.4, 180, .9, false));

        assertThat(response.feasible()).isTrue();
        assertThat(response.operations()).extracting(EvPlanOperation::type).containsExactly(EvPlanOperationType.ADD_CHARGER);
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("later day"));
    }

    @Test
    void aDisconnectedLaterDayIsNamedAsUncheckedAndLaterDaysAreIncluded() {
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var routes = mock(RouteService.class);
        var finder = mock(RouteCorridorChargerFinder.class);
        stubRoads(routes, (from, to) -> 30);
        when(finder.populateCandidates(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        var away = new EvRouteStopRequest("away", "Away", 13, 110, null, false, null);
        var awayEnd = new EvRouteStopRequest("away-end", "Away end", 13, 111, null, false, null);
        var after = new EvRouteStopRequest("after", "After", 13, 111, null, false, null);
        var afterEnd = new EvRouteStopRequest("after-end", "After end", 13, 112, null, false, null);
        var earlier = new EvRouteOptimizationRequest.Day("day-1", List.of(
                new EvRouteStopRequest("before", "Before", 13, 99, null, false, null), base.stops().getFirst()));
        var request = new EvRouteOptimizationRequest("day-2",
                List.of(new EvRouteStopRequest("origin", "Origin", 13, 100, null, false, null),
                        new EvRouteStopRequest("midpoint", "Midpoint", 13, 101, null, false, null)),
                base.vehicle(), 100.0, 10.0, 70.0, 20.0, List.of(earlier),
                List.of(new EvRouteOptimizationRequest.Day("day-3", List.of(away, awayEnd)),
                        new EvRouteOptimizationRequest.Day("day-4", List.of(after, afterEnd))));

        var response = new EvRouteOptimizationService(routes, finder, new SocConstrainedRouteOptimizer()).optimize(request);

        assertThat(response.feasible()).isTrue();
        // Day 3 of the trip, counting the earlier day and the selected one, and nothing after it is claimed.
        assertThat(response.warnings()).anyMatch(warning -> warning.contains("Day 3") && warning.contains("wasn't checked")
                && warning.contains("Later days"));
    }

    @Test
    void aDisconnectedLaterDayWithItsOwnReadingIsIndependentAndNeedsNoWarning() {
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var routes = mock(RouteService.class);
        var finder = mock(RouteCorridorChargerFinder.class);
        stubRoads(routes, (from, to) -> 30);
        when(finder.populateCandidates(any(), any(), any())).thenAnswer(call -> call.getArgument(0));
        var away = new EvRouteStopRequest("away", "Away", 13, 110, null, false, null, null, 60.0);
        var awayEnd = new EvRouteStopRequest("away-end", "Away end", 13, 111, null, false, null);
        var request = new EvRouteOptimizationRequest(base.blockId(), base.stops(), base.vehicle(), 80.0, 10.0, 70.0, 20.0,
                List.of(), List.of(new EvRouteOptimizationRequest.Day("day-2", List.of(away, awayEnd))));

        var response = new EvRouteOptimizationService(routes, finder, new SocConstrainedRouteOptimizer()).optimize(request);

        assertThat(response.feasible()).isTrue();
        assertThat(response.warnings()).noneMatch(warning -> warning.contains("wasn't checked"));
    }

    @Test
    void chargesLongerWhenThatIsWhatTheNextDayNeeds() {
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var nextStart = new EvRouteStopRequest("next-start", "Next start", 13, 103, null, false, null);
        var nextEnd = new EvRouteStopRequest("next-end", "Next end", 13, 104, null, false, null);
        var request = new EvRouteOptimizationRequest(base.blockId(), base.stops(), base.vehicle(), 80.0, 10.0, 70.0, 20.0,
                List.of(), List.of(new EvRouteOptimizationRequest.Day("day-2", List.of(nextStart, nextEnd))));
        var routes = mock(RouteService.class);
        var finder = mock(RouteCorridorChargerFinder.class);
        stubRoads(routes, (from, to) -> to.startsWith("ev-verify-") ? 140 : from.startsWith("ev-verify-") ? 160 : to.equals("next-end") ? 60 : 300);
        when(finder.populateCandidates(any(), any(), any())).thenReturn(new OptimizationRoute(List.of(new OptimizationRouteSpan(
                0, request.stops().getFirst(), request.stops().getLast(), 300, 14_400,
                List.of(new RouteCoordinate(100, 13), new RouteCoordinate(103, 13)),
                List.of(candidate(charger("mid", 13, 101.4, 180, .9, false), 140, 1, null))))));

        var response = new EvRouteOptimizationService(routes, finder, new SocConstrainedRouteOptimizer()).optimize(request);

        assertThat(response.feasible()).isTrue();
        assertThat(response.operations().getFirst().departureSocPct()).isEqualTo(85);
        assertThat(response.warnings()).noneMatch(warning -> warning.contains("later day"));
    }

    @Test
    void chargesLongerWhenTheRealRoadIsLongerThanTheCorridorEstimate() {
        var request = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var routes = mock(RouteService.class);
        var finder = mock(RouteCorridorChargerFinder.class);
        // The corridor estimates 161 km after the charger, but the road is 190 km.
        stubRoads(routes, (from, to) -> to.startsWith("ev-verify-") ? 140 : from.startsWith("ev-verify-") ? 190 : 330);
        when(finder.populateCandidates(any(), any(), any())).thenReturn(new OptimizationRoute(List.of(new OptimizationRouteSpan(
                0, request.stops().getFirst(), request.stops().getLast(), 300, 14_400,
                List.of(new RouteCoordinate(100, 13), new RouteCoordinate(103, 13)),
                List.of(candidate(charger("mid", 13, 101.4, 180, .9, false), 140, 1, null))))));

        var response = new EvRouteOptimizationService(routes, finder, new SocConstrainedRouteOptimizer()).optimize(request);

        assertThat(response.feasible()).isTrue();
        assertThat(response.finalSocPct()).isGreaterThanOrEqualTo(10);
    }

    @Test
    void aKeptStopsSavedChargeCountsSoTheSearchDoesNotAddARedundantCharger() {
        // The kept stop is already planned to leave at 90%, which reaches the destination.
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var origin = new EvRouteStopRequest("origin", "Origin", 13, 100, null, false, null);
        var kept = new EvRouteStopRequest("kept", "Kept stop", 13, 101, charger("kept-station", 13, 101, 50, .9, false),
                true, EvChargerSelectionSource.AUTO, null, null, 29, 28.8, 90.0);
        var destination = new EvRouteStopRequest("destination", "Destination", 13, 103, null, false, null);
        var request = new EvRouteOptimizationRequest("day-1", List.of(origin, kept, destination), base.vehicle(), 80.0, 10.0, 70.0, 20.0);
        var routes = mock(RouteService.class);
        var finder = mock(RouteCorridorChargerFinder.class);
        stubRoads(routes, (from, to) -> to.equals("kept") ? 90 : to.startsWith("ev-verify-") || from.startsWith("ev-verify-") ? 120 : 240);
        when(finder.populateCandidates(any(), any(), any())).thenReturn(new OptimizationRoute(List.of(
                span(0, origin, kept, 90, 5_400, List.of()),
                span(1, kept, destination, 240, 14_400, List.of(candidate(charger("fast", 13, 102, 180, .95, false), 120, 0, null))))));

        var response = new EvRouteOptimizationService(routes, finder, new SocConstrainedRouteOptimizer()).optimize(request);

        assertThat(response.feasible()).isTrue();
        assertThat(response.operations()).extracting(EvPlanOperation::type).doesNotContain(EvPlanOperationType.ADD_CHARGER);
        assertThat(response.finalSocPct()).isCloseTo(10, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void reportsAnUnchangedPlanAsAlreadyEfficientInsteadOfCountingRefreshesAsChanges() {
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var station = charger("saved-station", 13, 101.4, 180, .9, false);
        var saved = new EvRouteStopRequest("saved", "Saved", 13, 101.4, station, false, EvChargerSelectionSource.AUTO, null, null, 15, 15.0, 70.0);
        var request = new EvRouteOptimizationRequest("day-1", List.of(base.stops().getFirst(), saved, base.stops().getLast()), base.vehicle(), 80.0, 10.0, 70.0, 20.0);

        var response = optimize(request, candidate(station, 140, 1, "saved"));

        assertThat(response.feasible()).isTrue();
        assertThat(response.operations()).extracting(EvPlanOperation::type).containsExactly(EvPlanOperationType.UPDATE_CHARGER);
        assertThat(response.message()).isEqualTo("The current charging plan is already efficient.");
    }

    @Test
    void twoUnlockedVisitsToOneStationStayTheirOwnStopsOnTheirOwnSpans() {
        var station = charger("same-station", 13, 101, 180, .9, false);
        var origin = new EvRouteStopRequest("origin", "Origin", 13, 100, null, false, null);
        var outbound = new EvRouteStopRequest("visit-out", "Outbound", 13, 101, station, false, EvChargerSelectionSource.AUTO);
        var far = new EvRouteStopRequest("far", "Far", 13, 102, null, false, null);
        var inbound = new EvRouteStopRequest("visit-back", "Return", 13, 101, station, false, EvChargerSelectionSource.AUTO);
        var home = new EvRouteStopRequest("home", "Home", 13, 100, null, false, null);
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var request = new EvRouteOptimizationRequest("day-1", List.of(origin, outbound, far, inbound, home), base.vehicle(), 80.0, 10.0, 70.0, 20.0);
        var route = new OptimizationRoute(List.of(span(0, origin, far, 220, 7_200, List.of()), span(1, far, home, 220, 7_200, List.of())));

        for (var found : List.of(List.of(station), List.<EvChargerResponse>of())) {
            var service = mock(com.navio.mobilityandevservice.service.EvChargerService.class);
            when(service.nearby(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                    org.mockito.ArgumentMatchers.anyDouble(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                    .thenReturn(new com.navio.mobilityandevservice.domain.ev.EvChargerListResponse(found, null));

            var result = new RouteCorridorChargerFinder(service).populateCandidates(route, request, List.of(outbound, inbound));

            assertThat(result.spans().get(0).candidates()).extracting(OptimizationChargerCandidate::existingItemId).containsExactly("visit-out");
            assertThat(result.spans().get(1).candidates()).extracting(OptimizationChargerCandidate::existingItemId).containsExactly("visit-back");
        }
    }

    @Test
    void aSavedStopTheLiveSearchReportsClosedIsNotKeptFromItsSnapshot() {
        var active = charger("station", 13, 101, 180, .9, false);
        var closed = new EvChargerResponse(active.id(), active.name(), active.operatorName(), active.location(), active.address(), active.province(),
                active.connectorTypes(), active.maxKw(), active.totalConnectors(), active.availableConnectors(), active.priceText(), active.openingHours(),
                active.source(), active.verificationStatus(), EvChargerStatus.PERMANENTLY_CLOSED, active.ratingAvg(), active.ratingCount(), active.confidenceScore(), active.stale());
        var saved = new EvRouteStopRequest("saved", "Saved", 13, 101, active, false, EvChargerSelectionSource.AUTO);
        var base = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var request = new EvRouteOptimizationRequest("day-1", List.of(base.stops().getFirst(), saved, base.stops().getLast()), base.vehicle(), 80.0, 10.0, 70.0, 20.0);
        var service = mock(com.navio.mobilityandevservice.service.EvChargerService.class);
        when(service.nearby(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(new com.navio.mobilityandevservice.domain.ev.EvChargerListResponse(List.of(closed), null));
        var route = new OptimizationRoute(List.of(span(0, base.stops().getFirst(), base.stops().getLast(), 300, 14_400, List.of())));

        var result = new RouteCorridorChargerFinder(service).populateCandidates(route, request, List.of(saved));

        assertThat(result.spans().getFirst().candidates()).isEmpty();
    }

    @Test
    void corridorSearchFindsAChargerBetweenTwoSamplePointsInsideTheAllowedDetour() {
        // 75 km along the route and 8 km to the side: inside a 20 km detour allowance.
        var station = charger("between-anchors", 13.0719, 100.6923, 150, .9, false);
        var request = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var service = mock(com.navio.mobilityandevservice.service.EvChargerService.class);
        when(service.nearby(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenAnswer(call -> {
                    double dLat = ((double) call.getArgument(0) - 13.0719) * 110.574;
                    double dLng = ((double) call.getArgument(1) - 100.6923) * 108.34;
                    boolean inRadius = Math.hypot(dLat, dLng) <= (double) call.getArgument(2);
                    return new com.navio.mobilityandevservice.domain.ev.EvChargerListResponse(inRadius ? List.of(station) : List.of(), null);
                });
        var route = new OptimizationRoute(List.of(new OptimizationRouteSpan(0, request.stops().getFirst(), request.stops().getLast(), 325, 14_400,
                List.of(new RouteCoordinate(100, 13), new RouteCoordinate(103, 13)), List.of())));

        var result = new RouteCorridorChargerFinder(service).populateCandidates(route, request, List.of());

        assertThat(result.spans().getFirst().candidates()).extracting(candidate -> candidate.charger().id()).containsExactly("between-anchors");
    }

    @Test
    void anOutageOfTheChargerSearchIsNotReportedAsARouteWithNoSafePlan() {
        var request = requestWithVehicleConnectors(EvConnectorType.CCS2);
        var routes = mock(RouteService.class);
        var chargers = mock(com.navio.mobilityandevservice.service.EvChargerService.class);
        stubRoadRoutes(routes);
        when(chargers.nearby(org.mockito.ArgumentMatchers.anyDouble(), org.mockito.ArgumentMatchers.anyDouble(),
                org.mockito.ArgumentMatchers.anyDouble(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean()))
                .thenThrow(new com.navio.mobilityandevservice.exception.ProviderUnavailableException("down", new RuntimeException("down")));

        var response = new EvRouteOptimizationService(routes, new RouteCorridorChargerFinder(chargers), new SocConstrainedRouteOptimizer()).optimize(request);

        assertThat(response.feasible()).isFalse();
        assertThat(response.message()).contains("Charger search");
    }

    private void stubRoads(RouteService service, java.util.function.ToLongBiFunction<String, String> kmBetween) {
        when(service.computeDirections(any())).thenAnswer(invocation -> {
            DirectionsRequest request = invocation.getArgument(0);
            var group = request.groups().getFirst();
            var legs = new ArrayList<RouteSegmentResponse>();
            for (int i = 1; i < group.points().size(); i++) {
                var from = group.points().get(i - 1);
                var to = group.points().get(i);
                long km = kmBetween.applyAsLong(from.id(), to.id());
                legs.add(new RouteSegmentResponse("leg-" + i, group.blockId(), from.id(), to.id(), from.name(), to.name(),
                        RouteSegmentStatus.ROUTED, null, km * 1000, km * 60, null));
            }
            return new DirectionsResponse(legs);
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
