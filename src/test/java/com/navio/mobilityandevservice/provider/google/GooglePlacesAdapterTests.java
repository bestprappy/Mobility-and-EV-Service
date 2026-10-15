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

    @Test void readerReturnsAtMostThreeDistinctPhotosWithTheirOwnAttribution() {
        server.expect(requestTo("https://places.googleapis.com/v1/places/garden"))
                .andRespond(withSuccess("""
                    {"id":"garden","displayName":{"text":"Garden"},"location":{"latitude":35.7,"longitude":139.7},
                    "photos":[{"name":"places/garden/photos/one","authorAttributions":[{"displayName":"One"}]},
                    {"name":"places/garden/photos/one","authorAttributions":[{"displayName":"One"}]},
                    {"name":"places/garden/photos/two","authorAttributions":[{"displayName":"Two"}]},
                    {"name":"places/garden/photos/three","authorAttributions":[{"displayName":"Three"}]},
                    {"name":"places/garden/photos/four"}]}
                    """, MediaType.APPLICATION_JSON));
        for (String name : java.util.List.of("one", "two", "three")) {
            server.expect(requestTo("https://places.googleapis.com/v1/places/garden/photos/" + name + "/media?maxWidthPx=960&skipHttpRedirect=true"))
                    .andRespond(withSuccess("{\"photoUri\":\"https://example.com/" + name + ".jpg\"}", MediaType.APPLICATION_JSON));
        }
        var result = adapter.reader("garden");
        assertThat(result.photos()).hasSize(3);
        assertThat(result.photos().get(1).attributions().getFirst().displayName()).isEqualTo("Two");
        assertThat(result.place().photoUrl()).isEqualTo("https://example.com/one.jpg");
        server.verify();
    }

    @Test void geographicPredictionsSubmitDiscoveryQueriesInsteadOfOpeningCountryCards() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:autocomplete"))
                .andRespond(withSuccess("""
                    {"suggestions":[{"placePrediction":{"placeId":"osaka","text":{"text":"Osaka, Japan"},"types":["locality","political"]}}]}
                    """,MediaType.APPLICATION_JSON));
        var suggestion=adapter.exploreSuggestions("Osaka","JP").getFirst();
        assertThat(suggestion.kind()).isEqualTo("query"); assertThat(suggestion.placeId()).isNull();
        assertThat(suggestion.value()).isEqualTo("Osaka, Japan"); server.verify();
    }

    @Test void exploreRequestsCountryEvidenceAndProviderCursorWithoutTouchingExistingSearch() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:searchText"))
                .andExpect(header("X-Goog-FieldMask",containsString("places.addressComponents")))
                .andExpect(content().string(containsString("\"pageToken\":\"next\"")))
                .andExpect(content().string(containsString("\"includedType\":\"restaurant\"")))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath("$.locationBias").doesNotExist())
                .andRespond(withSuccess("""
                  {"places":[{"id":"p","displayName":{"text":"Sushi"},"location":{"latitude":35.7,"longitude":139.7},
                  "types":["restaurant","food"],"primaryType":"restaurant",
                  "addressComponents":[{"shortText":"JP","longText":"Japan","types":["country"]}]}],"nextPageToken":"more"}
                  """,MediaType.APPLICATION_JSON));
        var page = adapter.exploreSearch("sushi in Japan","restaurant","JP","next");
        assertThat(page.nextPageToken()).isEqualTo("more");
        assertThat(page.items().getFirst().candidate().place().placeLocation().countryCode()).isEqualTo("JP");
        assertThat(page.items().getFirst().types()).contains("restaurant");
        server.verify();
    }

    @Test void exploreCountrySuggestionsDoNotAskForUnsupportedQueryPredictions() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:autocomplete"))
                .andExpect(content().string(containsString("\"includeQueryPredictions\":false")))
                .andExpect(content().string(containsString("\"includedRegionCodes\":[\"jp\"]")))
                .andRespond(withSuccess("{\"suggestions\":[]}",MediaType.APPLICATION_JSON));
        assertThat(adapter.exploreSuggestions("Osaka","JP")).isEmpty(); server.verify();
    }

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
    void discoveryUsesBoundedPopularityPoolAndPreservesPhotoAttributionWithoutLoadingMedia() {
        server.expect(requestTo("https://places.googleapis.com/v1/places:searchNearby"))
                .andExpect(header("X-Goog-FieldMask", containsString("places.businessStatus,places.photos")))
                .andExpect(content().string(containsString("\"rankPreference\":\"POPULARITY\"")))
                .andExpect(content().string(containsString("\"maxResultCount\":20")))
                .andRespond(withSuccess("""
                    {"places":[{"id":"p","displayName":{"text":"Museum"},
                    "location":{"latitude":13.7,"longitude":100.5},"businessStatus":"OPERATIONAL",
                    "photos":[{"name":"places/p/photos/photo","googleMapsUri":"https://maps.google.com/photo","authorAttributions":[{"displayName":"Photographer","uri":"https://example.com/author","photoUri":"https://example.com/avatar"}]}]}]}
                    """, MediaType.APPLICATION_JSON));
        var places = adapter.discovery(13.7,100.5,java.util.List.of("museum"));
        assertThat(places).hasSize(1);
        assertThat(places.getFirst().place().photoUrl()).isNull();
        assertThat(places.getFirst().photoGoogleMapsUri()).isEqualTo("https://maps.google.com/photo");
        assertThat(places.getFirst().photoAttributions().getFirst().photoUri()).isEqualTo("https://example.com/avatar");
        assertThat(places.getFirst().photoAttributions().getFirst().displayName()).isEqualTo("Photographer");
        server.verify();
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
                .andExpect(header("X-Goog-FieldMask", containsString("addressComponents")))
                .andRespond(withSuccess("""
                        {
                          "id": "place-123",
                          "displayName": {"text": "Central Park"},
                          "formattedAddress": "New York, NY, USA",
                          "addressComponents": [
                            {"longText":"New York", "shortText":"New York", "types":["locality", "political"]},
                            {"longText":"New York", "shortText":"NY", "types":["administrative_area_level_1", "political"]},
                            {"longText":"United States", "shortText":"US", "types":["country", "political"]}
                          ],
                          "location": {"latitude": 40.785091, "longitude": -73.968285},
                          "internationalPhoneNumber": "+1 212-310-6600",
                          "websiteUri": "https://www.centralparknyc.org/",
                          "regularOpeningHours": {
                            "openNow": true,
                            "weekdayDescriptions": ["Monday: Open 24 hours"]
                          },
                          "rating": 4.8,
                          "userRatingCount": 150000,
                          "primaryType": "park",
                          "primaryTypeDisplayName": {"text": "Park"}
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = adapter.getDetail("place-123", "session-123");

        assertThat(response.name()).isEqualTo("Central Park");
        assertThat(response.placeLocation().city()).isEqualTo("New York");
        assertThat(response.placeLocation().countryCode()).isEqualTo("US");
        assertThat(response.location().lat()).isEqualTo(40.785091);
        assertThat(response.location().lng()).isEqualTo(-73.968285);
        assertThat(response.openingHours().openNow()).isTrue();
        assertThat(response.category()).isEqualTo("Park");
        assertThat(response.primaryType()).isEqualTo("park");
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
