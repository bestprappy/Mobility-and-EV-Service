package com.navio.mobilityandevservice.provider.google;

import com.navio.mobilityandevservice.domain.place.PlaceProviderName;
import com.navio.mobilityandevservice.domain.place.PlaceSearchScope;
import com.navio.mobilityandevservice.domain.ev.EvChargerStatus;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GooglePlacesAdapterTests {

    private MockRestServiceServer server;
    private GooglePlacesAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://places.googleapis.com/v1")
                .defaultHeader("X-Goog-Api-Key", "test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new GooglePlacesAdapter(builder.build());
    }

    @Test
    void mapsAutocompletePredictionsAndPreservesSessionToken() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:autocomplete"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andRespond(withSuccess("""
                        {
                          "suggestions": [
                            {
                              "placePrediction": {
                                "placeId": "place-123",
                                "text": {"text": "Central Park, New York"},
                                "structuredFormat": {
                                  "mainText": {"text": "Central Park"},
                                  "secondaryText": {"text": "New York, NY, USA"}
                                },
                                "types": ["park", "tourist_attraction"]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = adapter.autocomplete(
                "central park",
                40.7,
                -73.9,
                "US",
                "session-123",
                PlaceSearchScope.ANY
        );

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().provider()).isEqualTo(PlaceProviderName.GOOGLE);
        assertThat(response.items().getFirst().providerPlaceId()).isEqualTo("place-123");
        assertThat(response.items().getFirst().mainText()).isEqualTo("Central Park");
        assertThat(response.items().getFirst().sessionToken()).isEqualTo("session-123");
        server.verify();
    }

    @Test
    void mapsPlaceDetailsToProviderIndependentContract() {
        server.expect(requestTo("https://places.googleapis.com/v1/places/place-123?sessionToken=session-123"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "id": "place-123",
                          "displayName": {"text": "Central Park"},
                          "formattedAddress": "New York, NY, USA",
                          "location": {"latitude": 40.785091, "longitude": -73.968285},
                          "internationalPhoneNumber": "+1 212-310-6600",
                          "websiteUri": "https://www.centralparknyc.org/",
                          "regularOpeningHours": {
                            "openNow": true,
                            "weekdayDescriptions": ["Monday: Open 24 hours"]
                          },
                          "rating": 4.8,
                          "userRatingCount": 150000,
                          "primaryTypeDisplayName": {"text": "Park"}
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = adapter.getDetail("place-123", "session-123");

        assertThat(response.name()).isEqualTo("Central Park");
        assertThat(response.location().lat()).isEqualTo(40.785091);
        assertThat(response.location().lng()).isEqualTo(-73.968285);
        assertThat(response.openingHours().openNow()).isTrue();
        assertThat(response.category()).isEqualTo("Park");
        assertThat(response.rating()).isEqualTo(4.8);
        server.verify();
    }

    @Test
    void queriesAndMapsNearbyEvChargingStations() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:searchNearby"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andExpect(header("X-Goog-FieldMask", containsString("places.evChargeOptions")))
                .andExpect(content().string(containsString(
                        "\"includedTypes\":[\"electric_vehicle_charging_station\"]"
                )))
                .andRespond(withSuccess("""
                        {
                          "places": [
                            {
                              "id": "charger-place-123",
                              "displayName": {"text": "CentralWorld EV Charging"},
                              "formattedAddress": "999 Rama I Rd, Bangkok, Thailand",
                              "location": {"latitude": 13.7466, "longitude": 100.5391},
                              "regularOpeningHours": {
                                "openNow": true,
                                "weekdayDescriptions": ["Monday: Open 24 hours"]
                              },
                              "businessStatus": "OPERATIONAL",
                              "rating": 4.4,
                              "userRatingCount": 12,
                              "evChargeOptions": {
                                "connectorCount": 6,
                                "connectorAggregation": [
                                  {
                                    "type": "EV_CONNECTOR_TYPE_CCS_COMBO_2",
                                    "maxChargeRateKw": 150,
                                    "count": 4,
                                    "availableCount": 2
                                  },
                                  {
                                    "type": "EV_CONNECTOR_TYPE_TYPE_2",
                                    "maxChargeRateKw": 22,
                                    "count": 2,
                                    "availableCount": 1
                                  }
                                ]
                              }
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var chargers = adapter.nearbyEvChargers(13.7563, 100.5018, 10_000);

        assertThat(chargers).hasSize(1);
        var charger = chargers.getFirst();
        assertThat(charger.id()).isEqualTo("charger-place-123");
        assertThat(charger.connectorTypes())
                .containsExactly(EvConnectorType.CCS2, EvConnectorType.TYPE2);
        assertThat(charger.maxKw()).isEqualTo(150);
        assertThat(charger.totalConnectors()).isEqualTo(6);
        assertThat(charger.availableConnectors()).isEqualTo(3);
        assertThat(charger.status()).isEqualTo(EvChargerStatus.ACTIVE);
        assertThat(charger.source()).isEqualTo("GOOGLE_PLACES");
        assertThat(charger.openingHours().summary()).contains("Open 24 hours");
        server.verify();
    }
}
