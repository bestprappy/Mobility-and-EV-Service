package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.cache.StaleAwareCache;
import com.navio.mobilityandevservice.domain.place.PlaceAutocompleteResponse;
import com.navio.mobilityandevservice.domain.place.PlaceDetailResponse;
import com.navio.mobilityandevservice.domain.place.PlaceProviderName;
import com.navio.mobilityandevservice.domain.place.PlaceSearchResponse;
import com.navio.mobilityandevservice.domain.place.PlaceSearchScope;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.provider.PlaceProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;

@Service
public class PlaceService {

    private static final String CIRCUIT_BREAKER_NAME = "googlePlaces";

    private final PlaceProvider placeProvider;
    private final CircuitBreaker circuitBreaker;
    private final StaleAwareCache<PlaceDetailResponse> detailCache;
    private final StaleAwareCache<PlaceSearchResponse> searchCache;

    public PlaceService(
            PlaceProvider placeProvider,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            @Qualifier("placeDetailCache") StaleAwareCache<PlaceDetailResponse> detailCache,
            @Qualifier("placeSearchCache") StaleAwareCache<PlaceSearchResponse> searchCache
    ) {
        this.placeProvider = placeProvider;
        this.circuitBreaker = circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME);
        this.detailCache = detailCache;
        this.searchCache = searchCache;
    }

    public PlaceAutocompleteResponse autocomplete(
            String query,
            Double lat,
            Double lng,
            String country,
            String sessionToken,
            PlaceSearchScope scope
    ) {
        requireCoordinatePair(lat, lng);
        return circuitBreaker.run(
                () -> placeProvider.autocomplete(query, lat, lng, country, sessionToken, scope),
                throwable -> {
                    throw unavailable("Place autocomplete is temporarily unavailable", throwable);
                }
        );
    }

    public PlaceDetailResponse getDetail(
            String providerPlaceId,
            PlaceProviderName provider,
            String sessionToken
    ) {
        requireGoogle(provider);
        String cacheKey = "detail:" + providerPlaceId;

        if (sessionToken != null && !sessionToken.isBlank()) {
            return circuitBreaker.run(
                    () -> {
                        PlaceDetailResponse response = placeProvider.getDetail(providerPlaceId, sessionToken);
                        detailCache.put(cacheKey, response);
                        return response;
                    },
                    throwable -> detailCache.getStale(cacheKey)
                            .orElseThrow(() -> unavailable("Place detail is temporarily unavailable", throwable))
            );
        }

        return detailCache.getFresh(cacheKey).orElseGet(() -> circuitBreaker.run(
                () -> {
                    PlaceDetailResponse response = placeProvider.getDetail(providerPlaceId, sessionToken);
                    detailCache.put(cacheKey, response);
                    return response;
                },
                throwable -> detailCache.getStale(cacheKey)
                        .orElseThrow(() -> unavailable("Place detail is temporarily unavailable", throwable))
        ));
    }

    public PlaceSearchResponse textSearch(String query, Double lat, Double lng) {
        requireCoordinatePair(lat, lng);
        String cacheKey = String.format(
                Locale.ROOT,
                "search:%s:%s:%s",
                normalizeQuery(query),
                coordinateKey(lat),
                coordinateKey(lng)
        );

        return cachedSearch(cacheKey, () -> placeProvider.textSearch(query, lat, lng));
    }

    public PlaceSearchResponse nearby(double lat, double lng, int radiusMeters) {
        String cacheKey = String.format(
                Locale.ROOT,
                "nearby:%s:%s:%d",
                coordinateKey(lat),
                coordinateKey(lng),
                radiusMeters
        );

        return cachedSearch(cacheKey, () -> placeProvider.nearby(lat, lng, radiusMeters));
    }

    private PlaceSearchResponse cachedSearch(
            String cacheKey,
            java.util.function.Supplier<PlaceSearchResponse> providerCall
    ) {
        return searchCache.getFresh(cacheKey).orElseGet(() -> circuitBreaker.run(
                () -> {
                    PlaceSearchResponse response = providerCall.get();
                    searchCache.put(cacheKey, response);
                    return response;
                },
                throwable -> searchCache.getStale(cacheKey)
                        .orElseThrow(() -> unavailable("Place search is temporarily unavailable", throwable))
        ));
    }

    private void requireCoordinatePair(Double lat, Double lng) {
        if ((lat == null) != (lng == null)) {
            throw new InvalidRequestException("lat and lng must be provided together");
        }
    }

    private void requireGoogle(PlaceProviderName provider) {
        if (provider != PlaceProviderName.GOOGLE) {
            throw new InvalidRequestException("Only the GOOGLE place provider is enabled");
        }
    }

    private ProviderUnavailableException unavailable(String message, Throwable throwable) {
        return new ProviderUnavailableException(message, throwable);
    }

    private String normalizeQuery(String query) {
        return query.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private String coordinateKey(Double coordinate) {
        if (coordinate == null) {
            return "none";
        }
        return BigDecimal.valueOf(coordinate)
                .setScale(4, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }
}
