package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.config.OpenTripPlannerProperties;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.provider.google.GoogleRoutesAdapter;
import com.navio.mobilityandevservice.provider.otp.OpenTripPlannerRoutesAdapter;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/** Keep provider failures isolated so a transit outage cannot disable road routing. */
@Primary
@Component
public class FallbackRouteProvider implements RouteProvider {
    private final GoogleRoutesAdapter google;
    private final OpenTripPlannerRoutesAdapter otp;
    private final OpenTripPlannerProperties properties;
    private final CircuitBreaker googleCircuit;
    private final CircuitBreaker googleTransitCircuit;
    private final CircuitBreaker otpCircuit;

    public FallbackRouteProvider(GoogleRoutesAdapter google, OpenTripPlannerRoutesAdapter otp,
                                OpenTripPlannerProperties properties, CircuitBreakerFactory<?, ?> factory) {
        this.google = google;
        this.otp = otp;
        this.properties = properties;
        googleCircuit = factory.create("googleRoutes");
        googleTransitCircuit = factory.create("googleTransit");
        otpCircuit = factory.create("openTripPlanner");
    }

    @Override
    public String cacheNamespace(RouteProfile profile) {
        return profile == RouteProfile.TRANSIT && properties.enabled()
                ? "google+otp:" + properties.networkId() : "google";
    }

    @Override
    public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points) {
        return computeRoute(profile, points, RouteQueryOptions.DEFAULT);
    }

    @Override
    public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points, RouteQueryOptions options) {
        CircuitBreaker primaryCircuit = profile == RouteProfile.TRANSIT ? googleTransitCircuit : googleCircuit;
        RoutePlan primary = primaryCircuit.run(() -> google.computeRoute(profile, points, options), failure -> null);
        if (profile == RouteProfile.TRANSIT && properties.enabled() && (primary == null || primary.legs().isEmpty())) {
            RoutePlan alternative = otpCircuit.run(() -> otp.computeRoute(profile, points, options), failure -> null);
            if (alternative != null) return alternative;
            // Let the service retain a stale confirmed journey rather than cache an outage as no service.
            throw new ProviderClientException("Transit providers are temporarily unavailable");
        }
        if (primary == null) throw new ProviderClientException("Google route calculation is temporarily unavailable");
        return primary;
    }
}
