package com.navio.mobilityandevservice.provider.google;

import com.navio.mobilityandevservice.domain.route.LegTravelMode;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointType;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

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
