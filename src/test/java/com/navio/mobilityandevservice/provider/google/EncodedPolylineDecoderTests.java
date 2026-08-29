package com.navio.mobilityandevservice.provider.google;

import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EncodedPolylineDecoderTests {

    @Test
    void decodesGoogleEncodedPolylineIntoLongitudeLatitudeCoordinates() {
        List<RouteCoordinate> coordinates = EncodedPolylineDecoder.decode(
                "_p~iF~ps|U_ulLnnqC_mqNvxq`@"
        );

        assertThat(coordinates).containsExactly(
                new RouteCoordinate(-120.2, 38.5),
                new RouteCoordinate(-120.95, 40.7),
                new RouteCoordinate(-126.453, 43.252)
        );
    }
}
