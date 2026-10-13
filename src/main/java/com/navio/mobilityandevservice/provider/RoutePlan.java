package com.navio.mobilityandevservice.provider;

import java.util.List;
import com.navio.mobilityandevservice.domain.route.TransitJourneyResponse;

public record RoutePlan(List<RouteLeg> legs, List<TransitJourneyResponse> journeys) {

    public RoutePlan {
        legs = legs == null ? List.of() : List.copyOf(legs);
        journeys = journeys == null ? List.of() : List.copyOf(journeys);
    }

    public RoutePlan(List<RouteLeg> legs) {
        this(legs, List.of());
    }
}
