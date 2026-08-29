package com.navio.mobilityandevservice.domain.place;

import java.util.List;

public record PlaceSearchResponse(List<PlaceDetailResponse> items) {

    public PlaceSearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
