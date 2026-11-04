package com.navio.mobilityandevservice.domain.ev;

import com.navio.mobilityandevservice.domain.place.OpeningHoursResponse;
import com.navio.mobilityandevservice.domain.place.PlaceLocationResponse;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        boolean stale,
        /** Each plug's own top speed where the provider gives one. Empty means not known per plug. */
        Map<EvConnectorType, Double> connectorPowerKw,
        /** When the provider last reported this status. Null for a snapshot with no recorded check. */
        Instant statusCheckedAt
) {

    public EvChargerResponse(String id, String name, String operatorName, PlaceLocationResponse location, String address, String province,
                             List<EvConnectorType> connectorTypes, double maxKw, int totalConnectors, Integer availableConnectors, String priceText,
                             OpeningHoursResponse openingHours, String source, String verificationStatus, EvChargerStatus status,
                             double ratingAvg, long ratingCount, double confidenceScore, boolean stale) {
        this(id, name, operatorName, location, address, province, connectorTypes, maxKw, totalConnectors, availableConnectors, priceText,
                openingHours, source, verificationStatus, status, ratingAvg, ratingCount, confidenceScore, stale, Map.of(), null);
    }

    public EvChargerResponse {
        connectorPowerKw = connectorPowerKw == null ? Map.of() : Map.copyOf(connectorPowerKw);
        connectorTypes = connectorTypes == null || connectorTypes.isEmpty()
                ? List.of(EvConnectorType.OTHER)
                : List.copyOf(connectorTypes);
        openingHours = openingHours == null ? OpeningHoursResponse.empty() : openingHours;
    }
}
