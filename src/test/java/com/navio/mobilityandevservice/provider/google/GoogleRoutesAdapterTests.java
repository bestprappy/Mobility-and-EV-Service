package com.navio.mobilityandevservice.provider.google;

import com.navio.mobilityandevservice.domain.route.LegTravelMode;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointType;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.time.Instant;
import com.navio.mobilityandevservice.provider.RouteQueryOptions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleRoutesAdapterTests {

    private MockRestServiceServer server;
    private GoogleRoutesAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://routes.googleapis.com/directions/v2:computeRoutes")
                .defaultHeader("X-Goog-Api-Key", "test-key");
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new GoogleRoutesAdapter(builder.build());
    }

    @Test
    void returnsAlternativeJourneysWithSchedulesFareWalkingAndExactStopCoordinates() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(jsonPath("$.computeAlternativeRoutes").value(true))
                .andExpect(jsonPath("$.departureTime").value("2026-10-13T20:00:00Z"))
                .andExpect(header("X-Goog-FieldMask", org.hamcrest.Matchers.containsString("routes.travelAdvisory.transitFare")))
                .andRespond(withSuccess("{\"routes\":[" + scheduledRoute("SUBWAY", "360") + ","
                        + scheduledRoute("BUS", "420") + "]}", MediaType.APPLICATION_JSON));
        var plan = adapter.computeRoute(RouteProfile.TRANSIT, TWO_STATIONS,
                new RouteQueryOptions(true, Instant.parse("2026-10-13T20:00:00Z")));
        assertThat(plan.journeys()).hasSize(2);
        var first = plan.journeys().getFirst();
        assertThat(first.departureTime()).isEqualTo(Instant.parse("2026-10-13T20:03:00Z"));
        assertThat(first.arrivalTime()).isEqualTo(Instant.parse("2026-10-13T20:31:00Z"));
        assertThat(first.durationSeconds()).isEqualTo(1680);
        assertThat(first.walkingSeconds()).isEqualTo(300);
        assertThat(first.fare()).isEqualByComparingTo("360");
        assertThat(first.currency()).isEqualTo("JPY");
        assertThat(first.timeZone()).isEqualTo("Asia/Tokyo");
        assertThat(first.steps().get(1).departureLocation().lng()).isEqualTo(139.74);
        assertThat(first.steps().get(1).headsign()).isEqualTo("Central");
        assertThat(first.geometry().coordinates()).hasSize(3);
        // The existing itinerary contract still measures movement; scheduled rows include waiting.
        assertThat(plan.legs().getFirst().durationSeconds()).isEqualTo(1320);
        server.verify();
    }

    @Test
    void railAlternativesNeverContainABusCandidate() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andRespond(withSuccess("{\"routes\":[" + scheduledRoute("BUS", "360") + ","
                        + scheduledRoute("SUBWAY", "420") + "]}", MediaType.APPLICATION_JSON));
        var plan = adapter.computeRoute(RouteProfile.TRANSIT, RAIL_STATIONS, new RouteQueryOptions(true, null));
        assertThat(plan.journeys()).hasSize(1);
        assertThat(plan.journeys().getFirst().steps().get(1).line().vehicleType()).isEqualTo("SUBWAY");
        server.verify();
    }

    @Test
    void busPreferenceFiltersRailAndReturnsAllConfirmedBusAlternatives() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(jsonPath("$.transitPreferences.allowedTravelModes[0]").value("BUS"))
                .andExpect(jsonPath("$.computeAlternativeRoutes").value(true))
                .andExpect(jsonPath("$.departureTime").doesNotExist())
                .andRespond(withSuccess("{\"routes\":[" + scheduledRoute("SUBWAY", "360") + ","
                        + scheduledRoute("BUS", "420") + "," + scheduledRoute("INTERCITY_BUS", "430") + "]}", MediaType.APPLICATION_JSON));
        var points = List.of(TWO_STATIONS.getFirst(), new RoutePointRequest("hotel", "Hotel", RoutePointType.PLACE,
                25.046, 121.517, LegTravelMode.BUS));
        var plan = adapter.computeRoute(RouteProfile.TRANSIT, points, new RouteQueryOptions(true, null));
        assertThat(plan.journeys()).hasSize(2);
        assertThat(plan.legs().getFirst().steps().get(1).line().vehicleType()).isEqualTo("BUS");
        assertThat(plan.journeys()).allMatch(journey -> journey.steps().get(1).line().vehicleType().contains("BUS"));
        server.verify();
    }

    @Test
    void explicitRailPreferenceWorksWithoutTheStationOnlyFlag() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(jsonPath("$.transitPreferences.allowedTravelModes[0]").value("RAIL"))
                .andRespond(withSuccess("{\"routes\":[" + transitRoute(ride("BUS")) + ","
                        + transitRoute(ride("SUBWAY")) + "]}", MediaType.APPLICATION_JSON));
        var points = List.of(TWO_STATIONS.getFirst(), new RoutePointRequest("hotel", "Hotel", RoutePointType.PLACE,
                25.046, 121.517, LegTravelMode.RAIL));
        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, points).legs().getFirst().steps().getFirst().line().vehicleType())
                .isEqualTo("SUBWAY");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBWAY", "FERRY", "SHARE_TAXI", "OTHER"})
    void busOnlyRejectsMixedJourneysIncludingNonBusTransfers(String vehicleType) {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andRespond(withSuccess("{\"routes\":[" + transitRoute(ride("BUS") + "," + ride(vehicleType)) + "]}", MediaType.APPLICATION_JSON));
        var points = List.of(TWO_STATIONS.getFirst(), new RoutePointRequest("hotel", "Hotel", RoutePointType.PLACE,
                25.046, 121.517, LegTravelMode.BUS));
        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, points).legs()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"FEWER_TRANSFERS", "LESS_WALKING"})
    void requestsTheChosenTradeoffWithoutHardwiringFewerTransfers(String preference) {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(jsonPath("$.transitPreferences.routingPreference").value(preference))
                .andRespond(withSuccess("{\"routes\":[" + transitRoute(ride("SUBWAY")) + "]}", MediaType.APPLICATION_JSON));
        adapter.computeRoute(RouteProfile.TRANSIT, RAIL_STATIONS, new RouteQueryOptions(true, null,
                com.navio.mobilityandevservice.domain.route.TransitRoutingPreference.valueOf(preference)));
        server.verify();
    }

    @Test
    void leavesUnknownFareAndScheduleAbsentInsteadOfInventingZeroValues() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andRespond(withSuccess("{\"routes\":[" + transitRoute(ride("SUBWAY")) + "]}", MediaType.APPLICATION_JSON));
        var journey = adapter.computeRoute(RouteProfile.TRANSIT, TWO_STATIONS,
                new RouteQueryOptions(true, null)).journeys().getFirst();
        assertThat(journey.fare()).isNull();
        assertThat(journey.currency()).isNull();
        assertThat(journey.departureTime()).isNull();
        assertThat(journey.arrivalTime()).isNull();
        assertThat(journey.steps().getFirst().departureLocation()).isNull();
        server.verify();
    }

    private static String scheduledRoute(String vehicleType, String fare) {
        return """
                {"polyline":{"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"},
                 "travelAdvisory":{"transitFare":{"currencyCode":"JPY","units":"%s","nanos":0}},
                 "legs":[{"distanceMeters":6000,"duration":"1800s","steps":[
                   {"travelMode":"WALK","staticDuration":"120s"},
                   {"travelMode":"TRANSIT","staticDuration":"1020s",
                    "transitDetails":{"stopDetails":{
                       "departureStop":{"name":"Boarding Station","location":{"latLng":{"latitude":35.65,"longitude":139.74}}},
                       "arrivalStop":{"name":"Arrival Station","location":{"latLng":{"latitude":35.68,"longitude":139.68}}},
                       "departureTime":"2026-10-13T20:05:00Z","arrivalTime":"2026-10-13T20:28:00Z"},
                    "headsign":"Central","localizedValues":{"departureTime":{"timeZone":"Asia/Tokyo"}},
                    "transitLine":{"name":"Test Line","vehicle":{"type":"%s"}}}},
                   {"travelMode":"WALK","staticDuration":"180s"}]}]}
                """.formatted(fare, vehicleType);
    }

    @Test
    void mapsGoogleRouteLegsToPlannerGeometry() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Goog-Api-Key", "test-key"))
                .andRespond(withSuccess("""
                        {
                          "routes": [
                            {
                              "distanceMeters": 1200,
                              "duration": "123.4s",
                              "legs": [
                                {
                                  "distanceMeters": 1200,
                                  "duration": "123.4s",
                                  "polyline": {
                                    "encodedPolyline": "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
                                  }
                                }
                              ]
                            }
                          ]
                        }
                        """, MediaType.APPLICATION_JSON));

        var response = adapter.computeRoute(RouteProfile.DRIVING_TRAFFIC, List.of(
                new RoutePointRequest("a", "A", RoutePointType.PLACE, 38.5, -120.2),
                new RoutePointRequest("b", "B", RoutePointType.PLACE, 43.252, -126.453)
        ));

        assertThat(response.legs()).hasSize(1);
        assertThat(response.legs().getFirst().distanceMeters()).isEqualTo(1200);
        assertThat(response.legs().getFirst().durationSeconds()).isEqualTo(123);
        assertThat(response.legs().getFirst().coordinates()).hasSize(3);
        server.verify();
    }

    private static final List<RoutePointRequest> TWO_STATIONS = List.of(
            new RoutePointRequest("a", "Airport Terminal 2", RoutePointType.PLACE, 25.077, 121.232),
            new RoutePointRequest("b", "Taipei Main Station", RoutePointType.PLACE, 25.046, 121.517)
    );

    private static final List<RoutePointRequest> RAIL_STATIONS = List.of(
            TWO_STATIONS.getFirst(),
            new RoutePointRequest("b", "Taipei Main Station", RoutePointType.PLACE,
                    25.046, 121.517, LegTravelMode.TRANSIT, true)
    );

    @Test
    void requestsRailAlternativesAndSelectsThePurpleMrtInsteadOfTheFirstBusRoute() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(jsonPath("$.travelMode").value("TRANSIT"))
                .andExpect(jsonPath("$.computeAlternativeRoutes").value(true))
                .andExpect(jsonPath("$.transitPreferences.allowedTravelModes[0]").value("RAIL"))
                .andExpect(jsonPath("$.intermediates").doesNotExist())
                .andRespond(withSuccess("{\"routes\":[" + transitRoute(ride("BUS")) + ","
                        + transitRoute(ride("SUBWAY")) + "]}", MediaType.APPLICATION_JSON));

        var leg = adapter.computeRoute(RouteProfile.TRANSIT, RAIL_STATIONS).legs().getFirst();

        assertThat(leg.steps()).hasSize(1);
        assertThat(leg.steps().getFirst().line().vehicleType()).isEqualTo("SUBWAY");
        assertThat(leg.steps().getFirst().line().color()).isEqualTo("#8246af");
        assertThat(leg.coordinates()).hasSize(3);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"BUS", "INTERCITY_BUS", "TROLLEYBUS", "SHARE_TAXI", "FERRY", "OTHER"})
    void rejectsNonRailRidesEvenWhenTheyAreOnlyOneTransferInAnOtherwiseRailRoute(String vehicleType) {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andRespond(withSuccess("{\"routes\":[" + transitRoute(ride("SUBWAY") + "," + ride(vehicleType))
                        + "]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, RAIL_STATIONS).legs()).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUBWAY", "RAIL", "HEAVY_RAIL", "COMMUTER_TRAIN", "HIGH_SPEED_TRAIN",
            "LONG_DISTANCE_TRAIN", "METRO_RAIL", "MONORAIL", "TRAM"})
    void acceptsRailTransfersAndStationAccessWalking(String vehicleType) {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andRespond(withSuccess("{\"routes\":[" + transitRoute("""
                        {"travelMode":"WALK","staticDuration":"30s"},
                        """ + ride(vehicleType) + "," + ride("SUBWAY")) + "]}", MediaType.APPLICATION_JSON));

        var leg = adapter.computeRoute(RouteProfile.TRANSIT, RAIL_STATIONS).legs().getFirst();

        assertThat(leg.steps()).hasSize(3);
        assertThat(leg.durationSeconds()).isEqualTo(150);
        server.verify();
    }

    @Test
    void rejectsWalkingOnlyRoutesAndUnidentifiedTransitVehicles() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andRespond(withSuccess("{\"routes\":[" + transitRoute("{\"travelMode\":\"WALK\"}") + ","
                        + transitRoute("{\"travelMode\":\"TRANSIT\",\"transitDetails\":{}}") + ","
                        + transitRoute("") + "]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, RAIL_STATIONS).legs()).isEmpty();
        server.verify();
    }

    @Test
    void keepsBusesAvailableForGeneralPublicTransit() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(jsonPath("$.computeAlternativeRoutes").value(false))
                .andExpect(jsonPath("$.transitPreferences").doesNotExist())
                .andRespond(withSuccess("{\"routes\":[" + transitRoute(ride("BUS")) + "]}", MediaType.APPLICATION_JSON));

        var leg = adapter.computeRoute(RouteProfile.TRANSIT, TWO_STATIONS).legs().getFirst();

        assertThat(leg.steps().getFirst().line().vehicleType()).isEqualTo("BUS");
        server.verify();
    }

    @Test
    void ignoresTheRailRestrictionWhenTheTravellerDrivesInstead() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(jsonPath("$.travelMode").value("DRIVE"))
                .andExpect(jsonPath("$.computeAlternativeRoutes").value(false))
                .andExpect(jsonPath("$.transitPreferences").doesNotExist())
                .andRespond(withSuccess("{\"routes\":[" + transitRoute("") + "]}", MediaType.APPLICATION_JSON));

        assertThat(adapter.computeRoute(RouteProfile.DRIVING, RAIL_STATIONS).legs()).hasSize(1);
        server.verify();
    }

    private static String transitRoute(String steps) {
        return """
                {"legs":[{"distanceMeters":40100,"duration":"21600s",
                  "polyline":{"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"},
                  "steps":[%s]}]}
                """.formatted(steps);
    }

    private static String ride(String vehicleType) {
        return """
                {"travelMode":"TRANSIT","staticDuration":"60s",
                 "polyline":{"encodedPolyline":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"},
                 "transitDetails":{"transitLine":{"name":"Taoyuan Airport MRT","color":"#8246AF",
                   "vehicle":{"type":"%s"}}}}
                """.formatted(vehicleType);
    }

    @Test
    void mapsATransitLegToColouredRidesAndOneMergedWalkTimedWithoutTheInitialWait() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andExpect(header("X-Goog-FieldMask", org.hamcrest.Matchers.containsString("routes.legs.steps.transitDetails")))
                .andExpect(jsonPath("$.travelMode").value("TRANSIT"))
                .andExpect(jsonPath("$.intermediates").doesNotExist())
                .andExpect(jsonPath("$.routingPreference").doesNotExist())
                .andRespond(withSuccess("""
                        {
                          "routes": [{
                            "legs": [{
                              "distanceMeters": 40100,
                              "duration": "21600s",
                              "polyline": { "encodedPolyline": "_p~iF~ps|U_ulLnnqC_mqNvxq`@" },
                              "steps": [
                                {
                                  "travelMode": "TRANSIT",
                                  "distanceMeters": 39000,
                                  "staticDuration": "2280s",
                                  "polyline": { "encodedPolyline": "_p~iF~ps|U_ulLnnqC" },
                                  "transitDetails": {
                                    "stopDetails": {
                                      "departureStop": { "name": "Airport Terminal 2" },
                                      "arrivalStop": { "name": "Taipei Main Station" }
                                    },
                                    "headsign": "Taipei Main Station",
                                    "stopCount": 5,
                                    "transitLine": {
                                      "name": "Taoyuan Airport MRT",
                                      "nameShort": "A",
                                      "color": "#8246AF",
                                      "textColor": "#ffffff",
                                      "vehicle": { "type": "SUBWAY", "name": { "text": "Subway" } }
                                    }
                                  }
                                },
                                {
                                  "travelMode": "WALK",
                                  "distanceMeters": 700,
                                  "staticDuration": "500s",
                                  "polyline": { "encodedPolyline": "_ulLnnqC_mqNvxq`@" }
                                },
                                {
                                  "travelMode": "WALK",
                                  "distanceMeters": 400,
                                  "staticDuration": "300s",
                                  "polyline": { "encodedPolyline": "_ulLnnqC_mqNvxq`@" }
                                }
                              ]
                            }]
                          }]
                        }
                        """, MediaType.APPLICATION_JSON));

        var leg = adapter.computeRoute(RouteProfile.TRANSIT, TWO_STATIONS).legs().getFirst();

        assertThat(leg.durationSeconds()).isEqualTo(2280 + 500 + 300);
        assertThat(leg.steps()).hasSize(2);
        var ride = leg.steps().getFirst();
        assertThat(ride.travelMode()).isEqualTo(LegTravelMode.TRANSIT);
        assertThat(ride.line().name()).isEqualTo("Taoyuan Airport MRT");
        assertThat(ride.line().color()).isEqualTo("#8246af");
        assertThat(ride.line().vehicleType()).isEqualTo("SUBWAY");
        assertThat(ride.departureStop()).isEqualTo("Airport Terminal 2");
        assertThat(ride.stopCount()).isEqualTo(5);
        var walk = leg.steps().getLast();
        assertThat(walk.travelMode()).isEqualTo(LegTravelMode.WALK);
        assertThat(walk.distanceMeters()).isEqualTo(1100);
        assertThat(walk.line()).isNull();
        server.verify();
    }

    @Test
    void dropsALineColourThatIsNotAHexValue() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andRespond(withSuccess("""
                        { "routes": [{ "legs": [{
                          "polyline": { "encodedPolyline": "_p~iF~ps|U_ulLnnqC" },
                          "steps": [{
                            "travelMode": "TRANSIT", "staticDuration": "60s",
                            "polyline": { "encodedPolyline": "_p~iF~ps|U_ulLnnqC" },
                            "transitDetails": { "transitLine": { "name": "X", "color": "red;background:url(x)" } }
                          }]
                        }] }] }
                        """, MediaType.APPLICATION_JSON));

        var ride = adapter.computeRoute(RouteProfile.TRANSIT, TWO_STATIONS).legs().getFirst().steps().getFirst();

        assertThat(ride.line().color()).isNull();
    }

    @Test
    void reportsNoTransitServiceAsAnEmptyPlanRatherThanAProviderFailure() {
        server.expect(requestTo("https://routes.googleapis.com/directions/v2:computeRoutes"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, TWO_STATIONS).legs()).isEmpty();
    }

    @Test
    void refusesATransitRouteWithIntermediateStops() {
        var threeStops = List.of(
                TWO_STATIONS.getFirst(),
                new RoutePointRequest("m", "Middle", RoutePointType.PLACE, 25.06, 121.4),
                TWO_STATIONS.getLast());

        assertThatThrownBy(() -> adapter.computeRoute(RouteProfile.TRANSIT, threeStops))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
