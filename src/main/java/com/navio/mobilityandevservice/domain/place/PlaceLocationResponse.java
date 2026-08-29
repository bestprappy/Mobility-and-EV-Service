package com.navio.mobilityandevservice.domain.place;

public record PlaceLocationResponse(
        double lat,
        double lng,
        String address,
        String placeId
) {
}
