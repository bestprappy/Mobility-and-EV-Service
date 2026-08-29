package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.cache.StaleAwareCache;
import com.navio.mobilityandevservice.domain.route.DirectionsRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointGroupRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointType;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import com.navio.mobilityandevservice.domain.route.RouteSegmentStatus;
import com.navio.mobilityandevservice.provider.RoutePlan;
import com.navio.mobilityandevservice.provider.RouteProvider;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteServiceTests {

    @Test
    @SuppressWarnings("unchecked")
    void returnsDirectLineFallbackWhenProviderAndStaleCacheAreUnavailable() {
        RouteProvider routeProvider = (profile, points) -> {
            throw new IllegalStateException("provider unavailable");
        };
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

        RouteService service = new RouteService(
                routeProvider,
                factory,
                new StaleAwareCache<>(
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(5),
                        10,
                        Clock.systemUTC()
                )
        );

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
}
