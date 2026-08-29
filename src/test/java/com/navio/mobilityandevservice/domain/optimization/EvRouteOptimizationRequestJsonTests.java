package com.navio.mobilityandevservice.domain.optimization;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.navio.mobilityandevservice.domain.ev.EvChargerStatus;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the wire contract Trip Planning posts to /internal/v1/ev-route/optimize. Trip Planning
 * models the charger with loose types (status as a plain string, connectors as plain strings), so a
 * mismatch here fails the whole request body and reaches the user as "optimization is unavailable"
 * rather than anything that points at the real cause.
 */
class EvRouteOptimizationRequestJsonTests {

    private static final String TRIP_PLANNING_BODY = """
            {
              "blockId": "day-1",
              "stops": [
                {
                  "itemId": "origin",
                  "name": "Origin",
                  "lat": 13.0,
                  "lng": 100.0,
                  "charger": null,
                  "locked": false,
                  "selectionSource": null
                },
                {
                  "itemId": "charger-1",
                  "name": "Saved charger",
                  "lat": 13.0,
                  "lng": 101.5,
                  "charger": {
                    "id": "station-1",
                    "name": "Saved charger",
                    "operatorName": "Operator",
                    "location": {
                      "lat": 13.0,
                      "lng": 101.5,
                      "address": "Address",
                      "placeId": "station-1"
                    },
                    "address": "Address",
                    "province": null,
                    "connectorTypes": ["CCS2", "TYPE2"],
                    "maxKw": 120.0,
                    "totalConnectors": 4,
                    "availableConnectors": 2,
                    "priceText": null,
                    "openingHours": { "summary": "Open 24 hours" },
                    "source": "TRIP_SNAPSHOT",
                    "verificationStatus": "STALE",
                    "status": "active",
                    "ratingAvg": 4.5,
                    "ratingCount": 10,
                    "confidenceScore": 0.35,
                    "stale": true
                  },
                  "locked": false,
                  "selectionSource": "MANUAL"
                },
                {
                  "itemId": "destination",
                  "name": "Destination",
                  "lat": 13.0,
                  "lng": 103.0,
                  "charger": null,
                  "locked": false,
                  "selectionSource": null
                }
              ],
              "vehicle": {
                "batteryKwh": 60.0,
                "consumptionKwhPer100km": 20.0,
                "maxAcKw": 11.0,
                "maxDcKw": 180.0,
                "connectorTypes": ["CCS2"]
              },
              "startingSocPct": 80.0,
              "reserveSocPct": 12.0,
              "targetSocPct": 70.0,
              "maximumDetourKm": 20.0
            }
            """;

    @Test
    void readsTheBodyTripPlanningSends() throws Exception {
        ObjectMapper objectMapper = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();

        EvRouteOptimizationRequest request =
                objectMapper.readValue(TRIP_PLANNING_BODY, EvRouteOptimizationRequest.class);

        assertThat(request.blockId()).isEqualTo("day-1");
        assertThat(request.stops()).hasSize(3);
        assertThat(request.vehicle().connectorTypes()).containsExactly(EvConnectorType.CCS2);

        EvRouteStopRequest chargerStop = request.stops().get(1);
        assertThat(chargerStop.chargerStop()).isTrue();
        assertThat(chargerStop.effectiveLocked()).isFalse();
        assertThat(chargerStop.effectiveSelectionSource()).isEqualTo(EvChargerSelectionSource.MANUAL);
        assertThat(chargerStop.charger().connectorTypes())
                .containsExactly(EvConnectorType.CCS2, EvConnectorType.TYPE2);
        // Trip Planning stores the status as the lower-case @JsonValue form.
        assertThat(chargerStop.charger().status()).isEqualTo(EvChargerStatus.ACTIVE);
        assertThat(chargerStop.charger().openingHours().summary()).isEqualTo("Open 24 hours");
    }
}
