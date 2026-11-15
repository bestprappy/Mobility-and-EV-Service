package com.navio.mobilityandevservice.domain.place;

import java.util.List;

/**
 * One page of an explicit, user-started Google Places text search. Transient: Navio does not
 * store or cache these items; only a later user action (Save, Add to plan) keeps the place ID.
 * {@code source} is always {@code GOOGLE}; catalogue ({@code NAVIO:}) identities never appear here.
 */
public record PlaceProviderSearchResponse(List<Item> items, String cursor, int page, int maxPages, String source) {
    public record Item(String providerPlaceId, String name, String address, double lat, double lng,
                       String category, String primaryType, String countryCode, String city, String googleMapsUri) {}
}
