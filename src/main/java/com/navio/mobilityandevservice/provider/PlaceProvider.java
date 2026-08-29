package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.domain.place.PlaceAutocompleteResponse;
import com.navio.mobilityandevservice.domain.place.PlaceDetailResponse;
import com.navio.mobilityandevservice.domain.place.PlaceSearchResponse;
import com.navio.mobilityandevservice.domain.place.PlaceSearchScope;

public interface PlaceProvider {

    PlaceAutocompleteResponse autocomplete(
            String query,
            Double lat,
            Double lng,
            String country,
            String sessionToken,
            PlaceSearchScope scope
    );

    PlaceDetailResponse getDetail(String providerPlaceId, String sessionToken);

    PlaceSearchResponse textSearch(String query, Double lat, Double lng);

    PlaceSearchResponse nearby(double lat, double lng, int radiusMeters);
}
