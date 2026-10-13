package com.navio.mobilityandevservice.provider.otp;

import com.navio.mobilityandevservice.config.OpenTripPlannerProperties;
import com.navio.mobilityandevservice.domain.route.*;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.provider.RouteQueryOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OpenTripPlannerRoutesAdapterTests {
    private static final String ENDPOINT = "http://localhost:8801/otp/gtfs/v1";
    private static final Instant DEPARTURE = Instant.parse("2026-10-13T01:00:00Z");
    private static final List<RoutePointRequest> POINTS = List.of(
            new RoutePointRequest("from", "Shinjuku", RoutePointType.PLACE, 35.69, 139.70),
            new RoutePointRequest("to", "Skytree", RoutePointType.PLACE, 35.71, 139.81, LegTravelMode.TRANSIT));
    private MockRestServiceServer server;
    private OpenTripPlannerRoutesAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder().baseUrl(ENDPOINT);
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new OpenTripPlannerRoutesAdapter(builder.build(), properties(true));
    }

    @Test
    void mapsScheduledRidesWalksStopsColoursAndElapsedTimeWithoutInventingFare() {
        expectNetworkModes();
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.variables.origin.location.coordinate.latitude").value(35.69))
                .andExpect(jsonPath("$.variables.dateTime.earliestDeparture").value(DEPARTURE.toString()))
                .andExpect(jsonPath("$.variables.modes.transitOnly").value(true))
                .andRespond(withSuccess(response(leg("WALK", false), leg("SUBWAY", true)), MediaType.APPLICATION_JSON));
        var result = adapter.computeRoute(RouteProfile.TRANSIT, POINTS, new RouteQueryOptions(true, DEPARTURE));
        assertThat(result.journeys()).hasSize(1);
        var journey = result.journeys().getFirst();
        assertThat(journey.provider()).isEqualTo("OTP");
        assertThat(journey.timeZone()).isEqualTo("Asia/Tokyo");
        assertThat(journey.durationSeconds()).isEqualTo(1800);
        assertThat(journey.walkingSeconds()).isEqualTo(300);
        assertThat(journey.fare()).isNull();
        assertThat(journey.currency()).isNull();
        var ride = journey.steps().getLast();
        assertThat(ride.departureLocation()).isEqualTo(new RouteCoordinate(139.7, 35.69));
        assertThat(ride.arrivalStop()).isEqualTo("Oshiage");
        assertThat(ride.stopCount()).isEqualTo(1);
        assertThat(ride.line().color()).isEqualTo("#ce045b");
        assertThat(ride.departureTime()).isEqualTo(DEPARTURE);
        assertThat(result.legs().getFirst().durationSeconds()).isEqualTo(600);
        server.verify();
    }

    @Test
    void railRequestFiltersBusTransfersEvenWhenTheEngineReturnsThem() {
        expectNetworkModes();
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.variables.modes.transit.transit[0].mode").value("RAIL"))
                .andRespond(withSuccess(response(leg("SUBWAY", true), leg("BUS", true)), MediaType.APPLICATION_JSON));
        var points = List.of(POINTS.getFirst(), new RoutePointRequest("to", "Skytree", RoutePointType.PLACE,
                35.71, 139.81, LegTravelMode.RAIL));
        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, points).legs()).isEmpty();
        server.verify();
    }

    @Test
    void explicitBusOverridesAnOldRailOnlyFlag() {
        expectNetworkModes();
        server.expect(requestTo(ENDPOINT))
                .andExpect(jsonPath("$.variables.modes.transit.transit[0].mode").value("BUS"))
                .andRespond(withSuccess(response(leg("BUS", true)), MediaType.APPLICATION_JSON));
        var points = List.of(POINTS.getFirst(), new RoutePointRequest("to", "Hotel", RoutePointType.PLACE,
                35.71, 139.81, LegTravelMode.BUS, true));
        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, points).legs()).hasSize(1);
    }

    @Test
    void rejectsWalkingOnlyResultsAndRidesWithoutGeometry() {
        expectNetworkModes();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(leg("WALK", false)), MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(leg("RAIL", true)
                .replace("_p~iF~ps|U_ulLnnqC_mqNvxq`@", "")), MediaType.APPLICATION_JSON));
        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, POINTS).legs()).isEmpty();
        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, POINTS).legs()).isEmpty();
    }

    @Test
    void graphqlErrorsAndHttpFailuresAreProviderFailuresRatherThanConfirmedNoService() {
        expectNetworkModes();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"errors\":[{\"message\":\"expired graph\"}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withServerError());
        assertThatThrownBy(() -> adapter.computeRoute(RouteProfile.TRANSIT, POINTS)).isInstanceOf(ProviderClientException.class);
        assertThatThrownBy(() -> adapter.computeRoute(RouteProfile.TRANSIT, POINTS)).isInstanceOf(ProviderClientException.class);
    }

    @Test
    void validEmptyResultsRemainEmptyAndAlternativeFlagDoesNotChangeLegacyLegs() {
        expectNetworkModes();
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess("{\"data\":{\"planConnection\":{\"edges\":[]}}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(ENDPOINT)).andRespond(withSuccess(response(leg("RAIL", true)), MediaType.APPLICATION_JSON));
        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, POINTS).legs()).isEmpty();
        var result = adapter.computeRoute(RouteProfile.TRANSIT, POINTS);
        assertThat(result.legs()).hasSize(1);
        assertThat(result.journeys()).hasSize(1); // Preserve source metadata for attribution on the itinerary map.
        assertThat(result.legs().getFirst().steps().getFirst().line().vehicleType()).isEqualTo("HEAVY_RAIL");
    }

    @Test
    void fewerTransfersPreferenceIsPassedToTheEngine() {
        expectNetworkModes();
        server.expect(requestTo(ENDPOINT)).andExpect(jsonPath("$.variables.preferences.transit.transfer.cost").value(300))
                .andRespond(withSuccess(response(leg("RAIL", true)), MediaType.APPLICATION_JSON));
        adapter.computeRoute(RouteProfile.TRANSIT, POINTS, new RouteQueryOptions(true, DEPARTURE, TransitRoutingPreference.FEWER_TRANSFERS));
    }

    @Test
    void disabledAdapterMakesNoHttpRequestAndRejectsOtherProfiles() {
        var disabled = new OpenTripPlannerRoutesAdapter(RestClient.builder().baseUrl(ENDPOINT).build(), properties(false));
        assertThat(disabled.computeRoute(RouteProfile.TRANSIT, POINTS).legs()).isEmpty();
        assertThatThrownBy(() -> adapter.computeRoute(RouteProfile.WALKING, POINTS)).isInstanceOf(IllegalArgumentException.class);
        server.verify();
    }

    static OpenTripPlannerProperties properties(boolean enabled) {
        return new OpenTripPlannerProperties(enabled, URI.create(ENDPOINT), Duration.ofSeconds(2), Duration.ofSeconds(8), "tokyo-test");
    }

    @Test
    void missingVehicleModeReturnsNoServiceWithoutCallingTheRoutingAlgorithm() {
        server.expect(requestTo(ENDPOINT)).andExpect(jsonPath("$.query").value("{ routes { mode } }"))
                .andRespond(withSuccess("{\"data\":{\"routes\":[{\"mode\":\"SUBWAY\"}]}}", MediaType.APPLICATION_JSON));
        var bus = List.of(POINTS.getFirst(), new RoutePointRequest("to", "Hotel", RoutePointType.PLACE,
                35.71, 139.81, LegTravelMode.BUS));
        assertThat(adapter.computeRoute(RouteProfile.TRANSIT, bus).legs()).isEmpty();
        server.verify();
    }

    private void expectNetworkModes() {
        server.expect(requestTo(ENDPOINT)).andExpect(jsonPath("$.query").value("{ routes { mode } }"))
                .andRespond(withSuccess("{\"data\":{\"routes\":[{\"mode\":\"RAIL\"},{\"mode\":\"SUBWAY\"},{\"mode\":\"BUS\"}]}}", MediaType.APPLICATION_JSON));
    }

    @Test
    @EnabledIfSystemProperty(named = "navio.test.otp.endpoint", matches = "http://localhost:[0-9]+/otp/gtfs/v1")
    void mapsLiveTokyoGraphWhenExplicitlyEnabled() {
        URI endpoint = URI.create(System.getProperty("navio.test.otp.endpoint"));
        var liveProperties = new OpenTripPlannerProperties(true, endpoint, Duration.ofSeconds(2), Duration.ofSeconds(8), "tokyo-live");
        var http = java.net.http.HttpClient.newBuilder().version(java.net.http.HttpClient.Version.HTTP_1_1).build();
        var live = new OpenTripPlannerRoutesAdapter(RestClient.builder().baseUrl(endpoint.toString())
                .requestFactory(new JdkClientHttpRequestFactory(http)).defaultHeader("Accept-Language", "en").build(), liveProperties);
        var result = live.computeRoute(RouteProfile.TRANSIT, POINTS, new RouteQueryOptions(true, Instant.now()));
        assertThat(result.journeys()).isNotEmpty();
        assertThat(result.journeys()).allSatisfy(journey -> {
            assertThat(journey.provider()).isEqualTo("OTP");
            assertThat(journey.steps()).anyMatch(step -> step.travelMode() == LegTravelMode.TRANSIT);
            assertThat(journey.geometry().coordinates()).hasSizeGreaterThan(2);
        });
    }

    private static String response(String... legs) {
        return """
                {"data":{"planConnection":{"edges":[{"node":{"duration":1800,
                "start":"2026-10-13T10:00:00+09:00","end":"2026-10-13T10:30:00+09:00","legs":[%s]}}]}}}
                """.formatted(String.join(",", legs));
    }

    private static String leg(String mode, boolean ride) {
        return """
                {"mode":"%s","transitLeg":%s,"distance":1000,"duration":300,
                "start":{"scheduledTime":"2026-10-13T10:00:00+09:00"},
                "end":{"scheduledTime":"2026-10-13T10:05:00+09:00"},
                "from":{"name":"Shinjuku","lat":35.69,"lon":139.70},
                "to":{"name":"Oshiage","lat":35.71,"lon":139.81},
                "route":{"longName":"Oedo Line","shortName":"E","color":"CE045B","textColor":"FFFFFF","agency":{"timezone":"Asia/Tokyo"}},
                "legGeometry":{"points":"_p~iF~ps|U_ulLnnqC_mqNvxq`@"},
                "stopCalls":[{"stopLocation":{"__typename":"Stop"}},{"stopLocation":{"__typename":"Stop"}}]}
                """.formatted(mode, ride);
    }
}
