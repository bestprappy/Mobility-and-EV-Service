package com.navio.mobilityandevservice.provider;

import java.time.Instant;
import com.navio.mobilityandevservice.domain.route.TransitRoutingPreference;

public record RouteQueryOptions(boolean includeTransitAlternatives, Instant departureTime, TransitRoutingPreference transitRoutingPreference) {
    public static final RouteQueryOptions DEFAULT = new RouteQueryOptions(false, null, null);
    public RouteQueryOptions(boolean includeTransitAlternatives, Instant departureTime) {
        this(includeTransitAlternatives, departureTime, null);
    }
}
