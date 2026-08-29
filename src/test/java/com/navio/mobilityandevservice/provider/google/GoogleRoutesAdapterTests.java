package com.navio.mobilityandevservice.provider.google;

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
}
