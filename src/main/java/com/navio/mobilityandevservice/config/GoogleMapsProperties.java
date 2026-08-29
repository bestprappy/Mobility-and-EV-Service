package com.navio.mobilityandevservice.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("navio.mobility.providers.google-maps")
public record GoogleMapsProperties(
        @NotBlank String apiKey,
        @NotNull @Valid Endpoint places,
        @NotNull @Valid Endpoint routes,
        @NotNull @Valid Cache cache
) {

    public record Endpoint(
            @NotNull URI baseUrl,
            @NotNull Duration connectTimeout,
            @NotNull Duration readTimeout
    ) {

        @AssertTrue(message = "connect-timeout and read-timeout must be positive")
        public boolean hasPositiveTimeouts() {
            return isPositive(connectTimeout) && isPositive(readTimeout);
        }
    }

    public record Cache(
            @NotNull Duration placeFreshTtl,
            @NotNull Duration placeStaleTtl,
            @NotNull Duration routeFreshTtl,
            @NotNull Duration routeStaleTtl,
            @Positive long maximumSize
    ) {

        @AssertTrue(message = "place cache TTLs must be positive and stale TTL must exceed fresh TTL")
        public boolean hasValidPlaceTtls() {
            return isValidTtlRange(placeFreshTtl, placeStaleTtl);
        }

        @AssertTrue(message = "route cache TTLs must be positive and stale TTL must exceed fresh TTL")
        public boolean hasValidRouteTtls() {
            return isValidTtlRange(routeFreshTtl, routeStaleTtl);
        }
    }

    private static boolean isValidTtlRange(Duration freshTtl, Duration staleTtl) {
        return isPositive(freshTtl)
                && isPositive(staleTtl)
                && staleTtl.compareTo(freshTtl) > 0;
    }

    private static boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
