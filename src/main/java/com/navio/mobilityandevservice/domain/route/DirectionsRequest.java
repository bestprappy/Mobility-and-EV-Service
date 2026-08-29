package com.navio.mobilityandevservice.domain.route;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record DirectionsRequest(
        RouteProfile profile,
        @NotNull @Size(min = 1, max = 10) List<@Valid RoutePointGroupRequest> groups
) {

    public DirectionsRequest {
        groups = groups == null ? null : List.copyOf(groups);
    }

    public RouteProfile effectiveProfile() {
        return profile == null ? RouteProfile.DRIVING_TRAFFIC : profile;
    }
}
