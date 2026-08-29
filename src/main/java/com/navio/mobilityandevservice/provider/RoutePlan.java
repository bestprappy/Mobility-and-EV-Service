package com.navio.mobilityandevservice.provider;

import java.util.List;

public record RoutePlan(List<RouteLeg> legs) {

    public RoutePlan {
        legs = legs == null ? List.of() : List.copyOf(legs);
    }
}
