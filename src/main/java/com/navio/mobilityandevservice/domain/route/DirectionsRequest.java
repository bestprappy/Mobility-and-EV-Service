package com.navio.mobilityandevservice.domain.route;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.time.Instant;

public record DirectionsRequest(
        RouteProfile profile,
        @NotNull @Size(min = 1, max = 10) List<@Valid RoutePointGroupRequest> groups,
        Boolean includeTransitAlternatives,
        Instant departureTime,
        TransitRoutingPreference transitRoutingPreference
) {

    public DirectionsRequest {
        groups = groups == null ? null : List.copyOf(groups);
    }

    public DirectionsRequest(RouteProfile profile, List<RoutePointGroupRequest> groups, Boolean includeTransitAlternatives, Instant departureTime) {
        this(profile, groups, includeTransitAlternatives, departureTime, null);
    }

    public DirectionsRequest(RouteProfile profile, List<RoutePointGroupRequest> groups) {
        this(profile, groups, false, null);
    }

    public RouteProfile effectiveProfile() {
        return profile == null ? RouteProfile.DRIVING_TRAFFIC : profile;
    }
}
