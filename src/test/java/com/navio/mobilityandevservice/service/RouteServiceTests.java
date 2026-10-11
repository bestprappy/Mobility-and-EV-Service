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
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteServiceTests {

    private static final RoutePointRequest HOTEL = point("hotel", 25.03, 121.50, null);

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
    void explainsAMissingTransitRouteInsteadOfBlamingTheRoad() {
        RouteService service = service((profile, points) -> new RoutePlan(List.of()));

        var segment = service.computeDirections(day(
                point("station-1", 25.04, 121.51, null),
                point("station-2", 25.05, 121.52, LegTravelMode.TRANSIT))).segments().getFirst();

        assertThat(segment.status()).isEqualTo(RouteSegmentStatus.FALLBACK);
        assertThat(segment.travelMode()).isEqualTo(LegTravelMode.TRANSIT);
        assertThat(segment.fallbackReason()).isEqualTo("No public transit route was found");
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

    @SuppressWarnings("unchecked")
    private static RouteService service(RouteProvider routeProvider) {
        CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(factory.create("googleRoutes")).thenReturn(circuitBreaker);
        when(circuitBreaker.run(any(Supplier.class), any(Function.class))).thenAnswer(invocation -> {
            Supplier<RoutePlan> supplier = invocation.getArgument(0);
            Function<Throwable, RoutePlan> fallback = invocation.getArgument(1);
            try {
                return supplier.get();
            } catch (Throwable throwable) {
                return fallback.apply(throwable);
            }
        });

        return new RouteService(
                routeProvider,
                factory,
                new StaleAwareCache<>(
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(5),
                        10,
                        Clock.systemUTC()
                )
        );
    }
}
