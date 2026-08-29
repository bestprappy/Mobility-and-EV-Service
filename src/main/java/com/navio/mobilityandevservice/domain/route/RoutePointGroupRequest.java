package com.navio.mobilityandevservice.domain.route;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record RoutePointGroupRequest(
        @NotBlank @Size(max = 128) String blockId,
        @NotNull @Size(min = 2, max = 25) List<@Valid RoutePointRequest> points
) {

    public RoutePointGroupRequest {
        points = points == null ? null : List.copyOf(points);
    }
}
