package com.navio.mobilityandevservice.domain.place;

import java.util.List;

public record PlaceDiscoveryCandidate(PlaceDetailResponse place, String businessStatus, String photoResource,
        List<PlaceDiscoveryResponse.Attribution> photoAttributions, String photoGoogleMapsUri, List<PlaceDiscoveryResponse.Photo> photos) {
    public PlaceDiscoveryCandidate(PlaceDetailResponse place, String businessStatus, String photoResource,
            List<PlaceDiscoveryResponse.Attribution> photoAttributions, String photoGoogleMapsUri) {
        this(place, businessStatus, photoResource, photoAttributions, photoGoogleMapsUri, List.of());
    }
    public PlaceDiscoveryCandidate(PlaceDetailResponse place, String businessStatus, String photoResource,
                                   List<PlaceDiscoveryResponse.Attribution> photoAttributions) {
        this(place, businessStatus, photoResource, photoAttributions, null);
    }
}
