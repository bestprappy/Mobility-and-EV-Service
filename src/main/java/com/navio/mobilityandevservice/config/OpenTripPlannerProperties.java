package com.navio.mobilityandevservice.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties("navio.mobility.providers.otp")
public record OpenTripPlannerProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:8801/otp/gtfs/v1") @NotNull URI endpoint,
        @DefaultValue("2s") @NotNull Duration connectTimeout,
        @DefaultValue("8s") @NotNull Duration readTimeout,
        @DefaultValue("local") @NotBlank @Pattern(regexp = "[a-zA-Z0-9_.-]{1,64}") String networkId
) {
    @AssertTrue(message = "OTP endpoint must be an absolute HTTP(S) URL without credentials, query or fragment")
    public boolean hasValidEndpoint() {
        return endpoint != null && endpoint.getHost() != null && endpoint.getUserInfo() == null
                && endpoint.getQuery() == null && endpoint.getFragment() == null
                && ("http".equalsIgnoreCase(endpoint.getScheme()) || "https".equalsIgnoreCase(endpoint.getScheme()));
    }

    @AssertTrue(message = "OTP HTTP timeouts must be positive")
    public boolean hasPositiveTimeouts() {
        return connectTimeout != null && connectTimeout.isPositive()
                && readTimeout != null && readTimeout.isPositive();
    }
}
