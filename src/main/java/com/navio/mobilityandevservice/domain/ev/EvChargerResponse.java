package com.navio.mobilityandevservice.domain.ev;

import com.navio.mobilityandevservice.domain.place.OpeningHoursResponse;
import com.navio.mobilityandevservice.domain.place.PlaceLocationResponse;

import java.util.List;

public record EvChargerResponse(
        String id,
        String name,
        String operatorName,
        PlaceLocationResponse location,
        String address,
        String province,
        List<EvConnectorType> connectorTypes,
        double maxKw,
        int totalConnectors,
        Integer availableConnectors,
        String priceText,
        OpeningHoursResponse openingHours,
        String source,
        String verificationStatus,
        EvChargerStatus status,
        double ratingAvg,
        long ratingCount,
        double confidenceScore,
        boolean stale
) {

    public EvChargerResponse {
        connectorTypes = connectorTypes == null || connectorTypes.isEmpty()
                ? List.of(EvConnectorType.OTHER)
                : List.copyOf(connectorTypes);
        openingHours = openingHours == null ? OpeningHoursResponse.empty() : openingHours;
    }
}
