package com.navio.mobilityandevservice.provider.google;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.provider.RouteLeg;
import com.navio.mobilityandevservice.provider.RoutePlan;
import com.navio.mobilityandevservice.provider.RouteProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
public class GoogleRoutesAdapter implements RouteProvider {

    private static final String ROUTE_FIELD_MASK = String.join(",",
            "routes.distanceMeters",
            "routes.duration",
            "routes.polyline.encodedPolyline",
            "routes.legs.distanceMeters",
            "routes.legs.duration",
            "routes.legs.polyline.encodedPolyline"
    );

    private final RestClient restClient;

    public GoogleRoutesAdapter(@Qualifier("googleRoutesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("At least two route points are required");
        }

        GoogleRouteRequest request = new GoogleRouteRequest(
                waypoint(points.getFirst()),
                waypoint(points.getLast()),
                points.subList(1, points.size() - 1).stream().map(this::waypoint).toList(),
                travelMode(profile),
                profile == RouteProfile.DRIVING_TRAFFIC ? "TRAFFIC_AWARE" : null,
                "HIGH_QUALITY",
                "ENCODED_POLYLINE",
                false
        );

        try {
            GoogleRoutesResponse response = restClient.post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-FieldMask", ROUTE_FIELD_MASK)
                    .body(request)
                    .retrieve()
                    .body(GoogleRoutesResponse.class);

            if (response == null || response.routes() == null || response.routes().isEmpty()) {
                throw new ProviderClientException("Google Routes returned no route");
            }

            GoogleRoute route = response.routes().getFirst();
            List<RouteLeg> legs = safeList(route.legs()).stream()
                    .map(this::mapLeg)
                    .toList();

            if (legs.isEmpty()) {
                throw new ProviderClientException("Google Routes returned no route legs");
            }

            return new RoutePlan(legs);
        } catch (ProviderClientException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ProviderClientException("Google route calculation failed", exception);
        }
    }

    private RouteLeg mapLeg(GoogleRouteLeg leg) {
        String encodedPolyline = leg.polyline() == null ? null : leg.polyline().encodedPolyline();
        return new RouteLeg(
                EncodedPolylineDecoder.decode(encodedPolyline),
                leg.distanceMeters() == null ? 0 : leg.distanceMeters(),
                parseDurationSeconds(leg.duration())
        );
    }

    private GoogleWaypoint waypoint(RoutePointRequest point) {
        return new GoogleWaypoint(new GoogleLocation(new GoogleLatLng(point.lat(), point.lng())));
    }

    private String travelMode(RouteProfile profile) {
        return switch (profile) {
            case DRIVING, DRIVING_TRAFFIC -> "DRIVE";
            case WALKING -> "WALK";
            case CYCLING -> "BICYCLE";
        };
    }

    private long parseDurationSeconds(String duration) {
        if (duration == null || !duration.endsWith("s")) {
            return 0;
        }
        try {
            return new BigDecimal(duration.substring(0, duration.length() - 1))
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new ProviderClientException("Google Routes returned an invalid duration", exception);
        }
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GoogleRouteRequest(
            GoogleWaypoint origin,
            GoogleWaypoint destination,
            List<GoogleWaypoint> intermediates,
            String travelMode,
            String routingPreference,
            String polylineQuality,
            String polylineEncoding,
            boolean computeAlternativeRoutes
    ) {
    }

    private record GoogleWaypoint(GoogleLocation location) {
    }

    private record GoogleLocation(GoogleLatLng latLng) {
    }

    private record GoogleLatLng(double latitude, double longitude) {
    }

    private record GoogleRoutesResponse(List<GoogleRoute> routes) {
    }

    private record GoogleRoute(
            Long distanceMeters,
            String duration,
            GooglePolyline polyline,
            List<GoogleRouteLeg> legs
    ) {
    }

    private record GoogleRouteLeg(
            Long distanceMeters,
            String duration,
            GooglePolyline polyline
    ) {
    }

    private record GooglePolyline(String encodedPolyline) {
    }
}
