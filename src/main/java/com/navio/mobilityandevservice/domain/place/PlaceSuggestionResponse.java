package com.navio.mobilityandevservice.domain.place;

import java.util.List;

public record PlaceSuggestionResponse(
        PlaceProviderName provider,
        String providerPlaceId,
        String mainText,
        String secondaryText,
        List<String> types,
        String sessionToken
) {

    public PlaceSuggestionResponse {
        types = types == null ? List.of() : List.copyOf(types);
    }
}
