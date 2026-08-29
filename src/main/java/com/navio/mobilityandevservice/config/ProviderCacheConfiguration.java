package com.navio.mobilityandevservice.config;

import com.navio.mobilityandevservice.cache.StaleAwareCache;
import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.place.PlaceDetailResponse;
import com.navio.mobilityandevservice.domain.place.PlaceSearchResponse;
import com.navio.mobilityandevservice.provider.RoutePlan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.List;

@Configuration(proxyBeanMethods = false)
public class ProviderCacheConfiguration {

    @Bean
    Clock providerCacheClock() {
        return Clock.systemUTC();
    }

    @Bean
    StaleAwareCache<PlaceDetailResponse> placeDetailCache(
            GoogleMapsProperties properties,
            Clock providerCacheClock
    ) {
        return new StaleAwareCache<>(
                properties.cache().placeFreshTtl(),
                properties.cache().placeStaleTtl(),
                properties.cache().maximumSize(),
                providerCacheClock
        );
    }

    @Bean
    StaleAwareCache<PlaceSearchResponse> placeSearchCache(
            GoogleMapsProperties properties,
            Clock providerCacheClock
    ) {
        return new StaleAwareCache<>(
                properties.cache().placeFreshTtl(),
                properties.cache().placeStaleTtl(),
                properties.cache().maximumSize(),
                providerCacheClock
        );
    }

    @Bean
    StaleAwareCache<List<EvChargerResponse>> evChargerSearchCache(
            GoogleMapsProperties properties,
            Clock providerCacheClock
    ) {
        return new StaleAwareCache<>(
                properties.cache().placeFreshTtl(),
                properties.cache().placeStaleTtl(),
                properties.cache().maximumSize(),
                providerCacheClock
        );
    }

    @Bean
    StaleAwareCache<RoutePlan> routePlanCache(
            GoogleMapsProperties properties,
            Clock providerCacheClock
    ) {
        return new StaleAwareCache<>(
                properties.cache().routeFreshTtl(),
                properties.cache().routeStaleTtl(),
                properties.cache().maximumSize(),
                providerCacheClock
        );
    }
}
