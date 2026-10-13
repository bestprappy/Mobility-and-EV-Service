package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.cache.StaleAwareCache;
import com.navio.mobilityandevservice.domain.route.DirectionsRequest;
import com.navio.mobilityandevservice.domain.route.LegTravelMode;
import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import com.navio.mobilityandevservice.domain.route.RoutePointGroupRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointType;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import com.navio.mobilityandevservice.domain.route.RouteSegmentStatus;
import com.navio.mobilityandevservice.provider.RouteLeg;
import com.navio.mobilityandevservice.provider.RoutePlan;
import com.navio.mobilityandevservice.provider.RouteProvider;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import com.navio.mobilityandevservice.provider.RouteQueryOptions;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class RouteServiceTests {

    private static final RoutePointRequest HOTEL = point("hotel", 25.03, 121.50, null);

    @Test
    void separatesScheduledAlternativeQueriesFromDefaultRoutesAndDifferentDepartureTimes() {
        List<RouteQueryOptions> optionsSeen = new CopyOnWriteArrayList<>();
        RouteProvider provider = new RouteProvider() {
            @Override public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points) {
                return computeRoute(profile, points, RouteQueryOptions.DEFAULT);
            }
            @Override public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points, RouteQueryOptions options) {
                optionsSeen.add(options);
                return recordingProvider(new CopyOnWriteArrayList<>()).computeRoute(profile, points);
            }
        };
        RouteService service = service(provider);
        var groups = day(HOTEL, point("station", 25.05, 121.52, LegTravelMode.TRANSIT)).groups();
        Instant first = Instant.now().plusSeconds(3600);
        var scheduled = new DirectionsRequest(null, groups, true, first);
        service.computeDirections(scheduled);
        service.computeDirections(scheduled);
        service.computeDirections(new DirectionsRequest(null, groups, true, first.plusSeconds(60)));
        service.computeDirections(new DirectionsRequest(null, groups));
        assertThat(optionsSeen).containsExactly(new RouteQueryOptions(true, first),
                new RouteQueryOptions(true, first.plusSeconds(60)), RouteQueryOptions.DEFAULT);
    }

    @Test
    void rejectsOutOfWindowDepartureBeforeCallingTheProvider() {
        RouteProvider provider = mock(RouteProvider.class);
        RouteService service = service(provider);
        var groups = day(HOTEL, point("station", 25.05, 121.52, LegTravelMode.TRANSIT)).groups();
        assertThatThrownBy(() -> service.computeDirections(new DirectionsRequest(null, groups, true,
                Instant.now().plus(Duration.ofDays(101))))).isInstanceOf(InvalidRequestException.class);
        org.mockito.Mockito.verifyNoInteractions(provider);
    }

    @Test
    void returnsDirectLineFallbackWhenProviderAndStaleCacheAreUnavailable() {
        RouteService service = service((profile, points) -> {
            throw new IllegalStateException("provider unavailable");
        });

        var response = service.computeDirections(new DirectionsRequest(
                RouteProfile.DRIVING,
                List.of(new RoutePointGroupRequest("block-1", List.of(
                        new RoutePointRequest("a", "A", RoutePointType.PLACE, 13.7563, 100.5018),
                        new RoutePointRequest("b", "B", RoutePointType.PLACE, 13.7466, 100.5391)
                )))
        ));

        assertThat(response.segments()).hasSize(1);
        assertThat(response.segments().getFirst().status()).isEqualTo(RouteSegmentStatus.FALLBACK);
        assertThat(response.segments().getFirst().geometry().coordinates()).hasSize(2);
        assertThat(response.segments().getFirst().distanceMeters()).isPositive();
        assertThat(response.segments().getFirst().fallbackReason()).isNotBlank();
    }

    @Test
    void keepsAnAllDrivingDayInOneProviderCallWithItsIntermediateStops() {
        List<ProviderCall> calls = new CopyOnWriteArrayList<>();
        RouteService service = service(recordingProvider(calls));

        var response = service.computeDirections(day(
                HOTEL, point("a", 25.04, 121.51, null), point("b", 25.05, 121.52, LegTravelMode.DRIVE)));

        assertThat(calls).containsExactly(new ProviderCall(RouteProfile.DRIVING_TRAFFIC, 3));
        assertThat(response.segments()).extracting(segment -> segment.travelMode())
                .containsExactly(LegTravelMode.DRIVE, LegTravelMode.DRIVE);
    }

    @Test
    void routesEachTransitLegAloneAndKeepsTheDrivingLegsAroundItTogether() {
        List<ProviderCall> calls = new CopyOnWriteArrayList<>();
        RouteService service = service(recordingProvider(calls));

        var response = service.computeDirections(day(
                HOTEL,
                point("station-1", 25.04, 121.51, null),
                point("station-2", 25.05, 121.52, LegTravelMode.TRANSIT),
                point("station-3", 25.06, 121.53, LegTravelMode.TRANSIT),
                point("museum", 25.07, 121.54, LegTravelMode.WALK),
                point("dinner", 25.08, 121.55, null),
                point("hotel-end", 25.03, 121.50, null)));

        assertThat(calls).containsExactlyInAnyOrder(
                new ProviderCall(RouteProfile.DRIVING_TRAFFIC, 2),
                new ProviderCall(RouteProfile.TRANSIT, 2),
                new ProviderCall(RouteProfile.TRANSIT, 2),
                new ProviderCall(RouteProfile.WALKING, 2),
                new ProviderCall(RouteProfile.DRIVING_TRAFFIC, 3));
        assertThat(response.segments()).extracting(segment -> segment.toItemId())
                .containsExactly("station-1", "station-2", "station-3", "museum", "dinner", "hotel-end");
        assertThat(response.segments()).extracting(segment -> segment.travelMode())
                .containsExactly(LegTravelMode.DRIVE, LegTravelMode.TRANSIT, LegTravelMode.TRANSIT,
                        LegTravelMode.WALK, LegTravelMode.DRIVE, LegTravelMode.DRIVE);
    }

    @Test
    void routesBicycleLegsWithTheCyclingProfileAndKeepsTheirMode() {
        List<ProviderCall> calls = new CopyOnWriteArrayList<>();
        RouteService service = service(recordingProvider(calls));
        var response = service.computeDirections(day(HOTEL,
                point("park", 25.04, 121.51, LegTravelMode.BIKE),
                point("museum", 25.05, 121.52, LegTravelMode.BIKE)));
        assertThat(calls).containsExactly(new ProviderCall(RouteProfile.CYCLING, 3));
        assertThat(response.segments()).extracting(segment -> segment.travelMode())
                .containsExactly(LegTravelMode.BIKE, LegTravelMode.BIKE);
    }

    @Test
    void describesAnUnverifiedBicycleConnectionWithoutClaimingARoadRoute() {
        RouteService service = service((profile, points) -> new RoutePlan(List.of()));
        var segment = service.computeDirections(day(HOTEL,
                point("park", 25.04, 121.51, LegTravelMode.BIKE))).segments().getFirst();
        assertThat(segment.travelMode()).isEqualTo(LegTravelMode.BIKE);
        assertThat(segment.status()).isEqualTo(RouteSegmentStatus.FALLBACK);
        assertThat(segment.durationSeconds()).isNull();
        assertThat(segment.fallbackReason()).isEqualTo("Cycling route is unavailable");
    }

    @Test
    void explainsAMissingTransitRouteInsteadOfBlamingTheRoad() {
        RouteService service = service((profile, points) -> new RoutePlan(List.of()));

        var segment = service.computeDirections(day(
                point("station-1", 25.04, 121.51, null),
                point("station-2", 25.05, 121.52, LegTravelMode.TRANSIT))).segments().getFirst();

        assertThat(segment.status()).isEqualTo(RouteSegmentStatus.FALLBACK);
        assertThat(segment.travelMode()).isEqualTo(LegTravelMode.TRANSIT);
        assertThat(segment.fallbackReason()).isEqualTo("No public transit route was found");
    }

    @Test
    void railRequestsNeverReuseABusCapableTransitCacheEntry() {
        AtomicInteger providerCalls = new AtomicInteger();
        RouteService service = service((profile, points) -> {
            providerCalls.incrementAndGet();
            if (Boolean.TRUE.equals(points.getLast().railOnly())) {
                return new RoutePlan(List.of());
            }
            return recordingProvider(new CopyOnWriteArrayList<>()).computeRoute(profile, points);
        });
        RoutePointRequest origin = point("station-1", 25.04, 121.51, null);
        RoutePointRequest generalArrival = point("station-2", 25.05, 121.52, LegTravelMode.TRANSIT);
        RoutePointRequest railArrival = new RoutePointRequest("station-2", "station-2", RoutePointType.PLACE,
                25.05, 121.52, LegTravelMode.TRANSIT, true);

        assertThat(service.computeDirections(day(origin, generalArrival)).segments().getFirst().status())
                .isEqualTo(RouteSegmentStatus.ROUTED);
        var rail = service.computeDirections(day(origin, railArrival)).segments().getFirst();

        assertThat(rail.status()).isEqualTo(RouteSegmentStatus.FALLBACK);
        assertThat(rail.fallbackReason()).isEqualTo("No rail route was found");
        assertThat(rail.steps()).isEmpty();
        assertThat(rail.travelMode()).isEqualTo(LegTravelMode.TRANSIT);
        assertThat(service.computeDirections(day(origin, generalArrival)).segments().getFirst().status())
                .isEqualTo(RouteSegmentStatus.ROUTED);
        assertThat(service.computeDirections(day(origin, railArrival)).segments().getFirst().status())
                .isEqualTo(RouteSegmentStatus.FALLBACK);
        assertThat(providerCalls).hasValue(2);
    }

    @Test
    void railBusAndGeneralTransitUseSeparateCacheEntriesForTheSameAirportHotelLeg() {
        List<LegTravelMode> requested = new CopyOnWriteArrayList<>();
        RouteService service = service((profile, points) -> {
            requested.add(points.getLast().arrivalMode());
            assertThat(profile).isEqualTo(RouteProfile.TRANSIT);
            return recordingProvider(new CopyOnWriteArrayList<>()).computeRoute(profile, points);
        });
        for (LegTravelMode mode : List.of(LegTravelMode.TRANSIT, LegTravelMode.RAIL, LegTravelMode.BUS)) {
            var request = day(HOTEL, point("airport", 25.077, 121.232, mode));
            assertThat(service.computeDirections(request).segments().getFirst().travelMode()).isEqualTo(mode);
            service.computeDirections(request);
        }
        assertThat(requested).containsExactly(LegTravelMode.TRANSIT, LegTravelMode.RAIL, LegTravelMode.BUS);
    }

    @Test
    void preferredTransitRunsCannotContainIntermediateWaypoints() {
        List<ProviderCall> calls = new CopyOnWriteArrayList<>();
        var response = service(recordingProvider(calls)).computeDirections(day(HOTEL,
                point("a", 25.04, 121.51, LegTravelMode.RAIL), point("b", 25.05, 121.52, LegTravelMode.RAIL),
                point("c", 25.06, 121.53, LegTravelMode.BUS), point("d", 25.07, 121.54, LegTravelMode.BUS)));
        assertThat(calls).hasSize(4).allMatch(call -> call.profile() == RouteProfile.TRANSIT && call.points() == 2);
        assertThat(response.segments()).extracting(segment -> segment.travelMode())
                .containsExactly(LegTravelMode.RAIL, LegTravelMode.RAIL, LegTravelMode.BUS, LegTravelMode.BUS);
    }

    @Test
    void missingPreferredTransitKeepsItsModeAndExplainsItsUnavailableConnection() {
        RouteService service = service((profile, points) -> new RoutePlan(List.of()));
        for (LegTravelMode mode : List.of(LegTravelMode.RAIL, LegTravelMode.BUS)) {
            var segment = service.computeDirections(day(HOTEL, point("airport", 25.077, 121.232, mode))).segments().getFirst();
            assertThat(segment.status()).isEqualTo(RouteSegmentStatus.FALLBACK);
            assertThat(segment.travelMode()).isEqualTo(mode);
            assertThat(segment.fallbackReason()).isEqualTo(mode == LegTravelMode.RAIL ? "No rail route was found" : "No bus route was found");
            assertThat(segment.journeys()).isEmpty();
        }
    }

    private record ProviderCall(RouteProfile profile, int points) {
    }

    private static RouteProvider recordingProvider(List<ProviderCall> calls) {
        return (profile, points) -> {
            calls.add(new ProviderCall(profile, points.size()));
            return new RoutePlan(IntStream.range(0, points.size() - 1)
                    .mapToObj(index -> new RouteLeg(List.of(
                            new RouteCoordinate(points.get(index).lng(), points.get(index).lat()),
                            new RouteCoordinate(points.get(index + 1).lng(), points.get(index + 1).lat())),
                            1000, 60))
                    .toList());
        };
    }

    private static DirectionsRequest day(RoutePointRequest... points) {
        return new DirectionsRequest(null, List.of(new RoutePointGroupRequest("day-1", List.of(points))));
    }

    private static RoutePointRequest point(String id, double lat, double lng, LegTravelMode arrivalMode) {
        return new RoutePointRequest(id, id, RoutePointType.PLACE, lat, lng, arrivalMode);
    }

    private static RouteService service(RouteProvider routeProvider) {
        return new RouteService(
                routeProvider,
                new StaleAwareCache<>(
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(5),
                        10,
                        Clock.systemUTC()
                )
        );
    }
}
