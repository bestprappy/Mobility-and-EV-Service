package com.navio.mobilityandevservice.domain.route;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePointRequestJsonTests {

    @Test
    void keepsOlderDirectionsRequestsCompatibleAndReadsTheNewRailRestriction() {
        var mapper = JsonMapper.builder().build();
        var legacy = mapper.readValue("""
                {"id":"taipei","name":"Taipei Main Station","type":"place",
                 "lat":25.046,"lng":121.517,"arrivalMode":"transit"}
                """, RoutePointRequest.class);
        var rail = mapper.readValue("""
                {"id":"taipei","name":"Taipei Main Station","type":"place",
                 "lat":25.046,"lng":121.517,"arrivalMode":"transit","railOnly":true}
                """, RoutePointRequest.class);

        assertThat(legacy.arrivalMode()).isEqualTo(LegTravelMode.TRANSIT);
        assertThat(legacy.railOnly()).isNull();
        assertThat(rail.railOnly()).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(rail), RoutePointRequest.class)).isEqualTo(rail);
    }
}
