package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.cache.StaleAwareCache;
import com.navio.mobilityandevservice.domain.ev.EvChargerListMetaResponse;
import com.navio.mobilityandevservice.domain.ev.EvChargerListResponse;
import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.provider.EvChargerProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;

@Service
public class EvChargerService {

    private static final String CIRCUIT_BREAKER_NAME = "googlePlaces";

    private final EvChargerProvider evChargerProvider;
    private final CircuitBreaker circuitBreaker;
    private final StaleAwareCache<List<EvChargerResponse>> evChargerSearchCache;

    public EvChargerService(
            EvChargerProvider evChargerProvider,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            @Qualifier("evChargerSearchCache") StaleAwareCache<List<EvChargerResponse>> evChargerSearchCache
    ) {
        this.evChargerProvider = evChargerProvider;
        this.circuitBreaker = circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME);
        this.evChargerSearchCache = evChargerSearchCache;
    }

    public EvChargerListResponse nearby(
            double lat,
            double lng,
            double radiusKm,
            EvConnectorType connector,
            Double minKw,
            boolean refresh
    ) {
        int radiusMeters = Math.max(1, (int) Math.round(radiusKm * 1_000));
        String cacheKey = cacheKey(lat, lng, radiusMeters);

        if (!refresh) {
            List<EvChargerResponse> cached = evChargerSearchCache.getFresh(cacheKey).orElse(null);
            if (cached != null) {
                return response(cached, connector, minKw, "local_cache", cacheKey, false, false);
            }
        }

        return circuitBreaker.run(
                () -> {
                    List<EvChargerResponse> chargers = List.copyOf(
                            evChargerProvider.nearbyEvChargers(lat, lng, radiusMeters)
                    );
                    evChargerSearchCache.put(cacheKey, chargers);
                    return response(chargers, connector, minKw, "provider_refresh", cacheKey, false, true);
                },
                throwable -> evChargerSearchCache.getStale(cacheKey)
                        .map(chargers -> response(
                                chargers,
                                connector,
                                minKw,
                                "stale_cache",
                                cacheKey,
                                true,
                                false
                        ))
                        .orElseThrow(() -> new ProviderUnavailableException(
                                "EV station search is temporarily unavailable",
                                throwable
                        ))
        );
    }

    private EvChargerListResponse response(
            List<EvChargerResponse> chargers,
            EvConnectorType connector,
            Double minKw,
            String source,
            String cacheKey,
            boolean stale,
            boolean refreshed
    ) {
        List<EvChargerResponse> filtered = chargers.stream()
                .filter(charger -> connector == null || charger.connectorTypes().contains(connector))
                .filter(charger -> minKw == null || charger.maxKw() >= minKw)
                .map(charger -> stale ? withStaleFlag(charger) : charger)
                .toList();

        return new EvChargerListResponse(
                filtered,
                new EvChargerListMetaResponse(source, cacheKey, stale, refreshed)
        );
    }

    private EvChargerResponse withStaleFlag(EvChargerResponse charger) {
        return new EvChargerResponse(
                charger.id(),
                charger.name(),
                charger.operatorName(),
                charger.location(),
                charger.address(),
                charger.province(),
                charger.connectorTypes(),
                charger.maxKw(),
                charger.totalConnectors(),
                charger.availableConnectors(),
                charger.priceText(),
                charger.openingHours(),
                charger.source(),
                "STALE",
                charger.status(),
                charger.ratingAvg(),
                charger.ratingCount(),
                charger.confidenceScore(),
                true
        );
    }

    private String cacheKey(double lat, double lng, int radiusMeters) {
        return String.format(
                Locale.ROOT,
                "google_ev_%s_%s_%d",
                coordinateKey(lat),
                coordinateKey(lng),
                radiusMeters
        );
    }

    private String coordinateKey(double coordinate) {
        return BigDecimal.valueOf(coordinate)
                .setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
