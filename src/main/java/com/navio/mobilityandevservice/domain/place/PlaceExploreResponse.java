package com.navio.mobilityandevservice.domain.place;
import java.util.List;
public record PlaceExploreResponse(List<PlaceDiscoveryResponse.Item> items, List<String> recommendationIds,
        String cursor, String mode, String seasonalNotice, List<String> seasonalSources) {}
