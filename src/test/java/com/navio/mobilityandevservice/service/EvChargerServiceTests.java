package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.cache.StaleAwareCache;
import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvChargerStatus;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.domain.place.OpeningHoursResponse;
import com.navio.mobilityandevservice.domain.place.PlaceLocationResponse;
import com.navio.mobilityandevservice.provider.EvChargerProvider;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvChargerServiceTests {

    @Test
    @SuppressWarnings("unchecked")
    void cachesGoogleResultsAndAppliesConnectorAndPowerFilters() {
        AtomicInteger providerCalls = new AtomicInteger();
        EvChargerProvider provider = (lat, lng, radiusMeters) -> {
            providerCalls.incrementAndGet();
            return List.of(
                    charger("fast", List.of(EvConnectorType.CCS2), 150),
                    charger("destination", List.of(EvConnectorType.TYPE2), 22)
            );
        };
        CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
        CircuitBreaker circuitBreaker = mock(CircuitBreaker.class);
        when(factory.create("googlePlaces")).thenReturn(circuitBreaker);
        when(circuitBreaker.run(any(Supplier.class), any(Function.class))).thenAnswer(invocation -> {
            Supplier<Object> supplier = invocation.getArgument(0);
            Function<Throwable, Object> fallback = invocation.getArgument(1);
            try {
                return supplier.get();
            } catch (Throwable throwable) {
                return fallback.apply(throwable);
            }
        });

        EvChargerService service = new EvChargerService(
                provider,
                factory,
                new StaleAwareCache<>(
                        Duration.ofMinutes(1),
                        Duration.ofMinutes(5),
                        10,
                        Clock.systemUTC()
                )
        );

        var first = service.nearby(13.7563, 100.5018, 10, EvConnectorType.CCS2, 100.0, false);
        var second = service.nearby(13.7563, 100.5018, 10, EvConnectorType.TYPE2, null, false);

        assertThat(first.items()).extracting(EvChargerResponse::id).containsExactly("fast");
        assertThat(first.meta().source()).isEqualTo("provider_refresh");
        assertThat(first.meta().refreshed()).isTrue();
        assertThat(second.items()).extracting(EvChargerResponse::id).containsExactly("destination");
        assertThat(second.meta().source()).isEqualTo("local_cache");
        assertThat(providerCalls).hasValue(1);
    }

    private EvChargerResponse charger(String id, List<EvConnectorType> connectors, double maxKw) {
        return new EvChargerResponse(
                id,
                id,
                null,
                new PlaceLocationResponse(13.7563, 100.5018, "Bangkok", id),
                "Bangkok",
                null,
                connectors,
                maxKw,
                2,
                null,
                null,
                OpeningHoursResponse.empty(),
                "GOOGLE_PLACES",
                "GOOGLE_CACHED",
                EvChargerStatus.ACTIVE,
                0,
                0,
                0.82,
                false
        );
    }
}
