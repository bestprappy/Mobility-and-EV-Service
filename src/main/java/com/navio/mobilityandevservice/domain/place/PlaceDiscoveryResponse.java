package com.navio.mobilityandevservice.domain.place;

import java.util.List;

public record PlaceDiscoveryResponse(List<Item> items, boolean hasMore, Seasonal seasonal) {
    public record Item(PlaceDetailResponse place, String reason, String businessStatus,
                       List<Attribution> photoAttributions, String photoGoogleMapsUri, List<Photo> photos) {
        public Item(PlaceDetailResponse place, String reason, String businessStatus, List<Attribution> photoAttributions, String photoGoogleMapsUri) {
            this(place, reason, businessStatus, photoAttributions, photoGoogleMapsUri, List.of());
        }
    }
    public record Photo(String url, List<Attribution> attributions, String googleMapsUri) {}
    public record Attribution(String displayName, String uri, String photoUri) {}
    public record Seasonal(String label, String description, String sourceUrl) {}
}
