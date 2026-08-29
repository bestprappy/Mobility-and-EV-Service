package com.navio.mobilityandevservice.domain.place;

import java.util.List;

public record PlaceAutocompleteResponse(List<PlaceSuggestionResponse> items) {

    public PlaceAutocompleteResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
