package com.navio.mobilityandevservice.provider.google;

import com.navio.mobilityandevservice.domain.route.RouteCoordinate;

import java.util.ArrayList;
import java.util.List;

final class EncodedPolylineDecoder {

    private EncodedPolylineDecoder() {
    }

    static List<RouteCoordinate> decode(String encodedPolyline) {
        if (encodedPolyline == null || encodedPolyline.isBlank()) {
            return List.of();
        }

        List<RouteCoordinate> coordinates = new ArrayList<>();
        int index = 0;
        int latitude = 0;
        int longitude = 0;

        while (index < encodedPolyline.length()) {
            DecodedValue latitudeValue = decodeValue(encodedPolyline, index);
            index = latitudeValue.nextIndex();
            latitude += latitudeValue.delta();

            if (index >= encodedPolyline.length()) {
                throw new IllegalArgumentException("Encoded polyline ended before longitude value");
            }

            DecodedValue longitudeValue = decodeValue(encodedPolyline, index);
            index = longitudeValue.nextIndex();
            longitude += longitudeValue.delta();

            coordinates.add(new RouteCoordinate(
                    longitude / 100_000.0,
                    latitude / 100_000.0
            ));
        }

        return List.copyOf(coordinates);
    }

    private static DecodedValue decodeValue(String encodedPolyline, int startIndex) {
        int result = 0;
        int shift = 0;
        int index = startIndex;
        int value;

        do {
            if (index >= encodedPolyline.length()) {
                throw new IllegalArgumentException("Encoded polyline is truncated");
            }
            value = encodedPolyline.charAt(index++) - 63;
            result |= (value & 0x1f) << shift;
            shift += 5;
        } while (value >= 0x20);

        int delta = (result & 1) != 0 ? ~(result >> 1) : result >> 1;
        return new DecodedValue(delta, index);
    }

    private record DecodedValue(int delta, int nextIndex) {
    }
}
