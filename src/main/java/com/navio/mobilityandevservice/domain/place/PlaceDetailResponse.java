package com.navio.mobilityandevservice.domain.place;

public record PlaceDetailResponse(
        PlaceProviderName provider,
        String providerPlaceId,
        String name,
        String address,
        PlaceLocationResponse location,
        String phone,
        String website,
        String photoUrl,
        OpeningHoursResponse openingHours,
        String category,
        String description,
        Double rating,
        Long reviewCount
) {
}
