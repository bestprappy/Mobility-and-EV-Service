package com.navio.mobilityandevservice;

import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import com.navio.mobilityandevservice.domain.route.RouteLineString;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class MobilityAndEvServiceApplicationTests {

	@Autowired
	private ObjectMapper objectMapper;

	@Test
	void contextLoads() {
	}

	@Test
	void serializesRouteCoordinatesAsGeoJsonPositions() throws Exception {
		String json = objectMapper.writeValueAsString(new RouteLineString(List.of(
				new RouteCoordinate(100.5018, 13.7563),
				new RouteCoordinate(99.9577, 12.5684)
		)));

		assertThat(json).contains("\"coordinates\":[[100.5018,13.7563],[99.9577,12.5684]]");
	}

}
