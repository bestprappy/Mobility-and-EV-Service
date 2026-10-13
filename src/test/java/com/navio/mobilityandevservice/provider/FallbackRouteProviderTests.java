package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.config.OpenTripPlannerProperties;
import com.navio.mobilityandevservice.domain.route.*;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.provider.google.GoogleRoutesAdapter;
import com.navio.mobilityandevservice.provider.otp.OpenTripPlannerRoutesAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FallbackRouteProviderTests {
    private final GoogleRoutesAdapter google = mock(GoogleRoutesAdapter.class);
    private final OpenTripPlannerRoutesAdapter otp = mock(OpenTripPlannerRoutesAdapter.class);
    private final CircuitBreakerFactory<?, ?> factory = mock(CircuitBreakerFactory.class);
    private final List<RoutePointRequest> points = List.of();
    private final RoutePlan empty = new RoutePlan(List.of());
    private final RoutePlan routed = new RoutePlan(List.of(new RouteLeg(List.of(new RouteCoordinate(139.7, 35.69),
            new RouteCoordinate(139.8, 35.71)), 1000, 600)));

    @BeforeEach
    @SuppressWarnings("unchecked")
    void circuits() {
        for (String name : List.of("googleRoutes", "googleTransit", "openTripPlanner")) {
            CircuitBreaker breaker = mock(CircuitBreaker.class);
            when(factory.create(name)).thenReturn(breaker);
            when(breaker.run(any(Supplier.class), any(Function.class))).thenAnswer(call -> {
                Supplier<RoutePlan> source = call.getArgument(0);
                Function<Throwable, RoutePlan> fallback = call.getArgument(1);
                try { return source.get(); } catch (RuntimeException failure) { return fallback.apply(failure); }
            });
        }
    }

    @Test
    void usesOtpForEmptyGoogleTransitAndForGoogleOutages() {
        var provider = provider(true);
        when(google.computeRoute(RouteProfile.TRANSIT, points, RouteQueryOptions.DEFAULT)).thenReturn(empty)
                .thenThrow(new ProviderClientException("Google unavailable"));
        when(otp.computeRoute(RouteProfile.TRANSIT, points, RouteQueryOptions.DEFAULT)).thenReturn(routed);
        assertThat(provider.computeRoute(RouteProfile.TRANSIT, points)).isSameAs(routed);
        assertThat(provider.computeRoute(RouteProfile.TRANSIT, points)).isSameAs(routed);
        verify(otp, times(2)).computeRoute(RouteProfile.TRANSIT, points, RouteQueryOptions.DEFAULT);
    }

    @Test
    void successfulGoogleTransitAndEveryRoadModeSkipOtp() {
        var provider = provider(true);
        for (RouteProfile profile : RouteProfile.values()) {
            when(google.computeRoute(profile, points, RouteQueryOptions.DEFAULT)).thenReturn(routed);
            assertThat(provider.computeRoute(profile, points)).isSameAs(routed);
        }
        verifyNoInteractions(otp);
    }

    @Test
    void otpOutageDoesNotPoisonGoogleDrivingAndIsNotCachedAsAnEmptyAnswer() {
        var provider = provider(true);
        when(google.computeRoute(RouteProfile.TRANSIT, points, RouteQueryOptions.DEFAULT)).thenReturn(empty);
        when(otp.computeRoute(RouteProfile.TRANSIT, points, RouteQueryOptions.DEFAULT)).thenThrow(new ProviderClientException("OTP unavailable"));
        when(google.computeRoute(RouteProfile.DRIVING, points, RouteQueryOptions.DEFAULT)).thenReturn(routed);
        assertThatThrownBy(() -> provider.computeRoute(RouteProfile.TRANSIT, points)).isInstanceOf(ProviderClientException.class);
        assertThat(provider.computeRoute(RouteProfile.DRIVING, points)).isSameAs(routed);
        verify(factory).create("googleRoutes");
        verify(factory).create("googleTransit");
        verify(factory).create("openTripPlanner");
    }

    @Test
    void disabledConfigurationPreservesTheExistingGoogleBehavior() {
        var provider = provider(false);
        when(google.computeRoute(RouteProfile.TRANSIT, points, RouteQueryOptions.DEFAULT)).thenReturn(empty);
        assertThat(provider.computeRoute(RouteProfile.TRANSIT, points)).isSameAs(empty);
        verifyNoInteractions(otp);
        assertThat(provider.cacheNamespace(RouteProfile.TRANSIT)).isEqualTo("google");
    }

    @Test
    void cacheNamespaceIncludesTheConfiguredNetworkOnlyForTransit() {
        var provider = provider(true);
        assertThat(provider.cacheNamespace(RouteProfile.TRANSIT)).isEqualTo("google+otp:tokyo-core");
        assertThat(provider.cacheNamespace(RouteProfile.DRIVING)).isEqualTo("google");
    }

    @Test
    void forwardsAlternativeDepartureAndPreferenceOptions() {
        var provider = provider(true);
        var options = new RouteQueryOptions(true, java.time.Instant.now(), TransitRoutingPreference.LESS_WALKING);
        when(google.computeRoute(RouteProfile.TRANSIT, points, options)).thenReturn(empty);
        when(otp.computeRoute(RouteProfile.TRANSIT, points, options)).thenReturn(empty);
        assertThat(provider.computeRoute(RouteProfile.TRANSIT, points, options)).isSameAs(empty);
        verify(otp).computeRoute(RouteProfile.TRANSIT, points, options);
    }

    private FallbackRouteProvider provider(boolean enabled) {
        var properties = new OpenTripPlannerProperties(enabled, URI.create("http://localhost:8801/otp/gtfs/v1"),
                Duration.ofSeconds(2), Duration.ofSeconds(8), "tokyo-core");
        return new FallbackRouteProvider(google, otp, properties, factory);
    }
}
