package com.navio.mobilityandevservice.provider.google;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.navio.mobilityandevservice.domain.route.LegTravelMode;
import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import com.navio.mobilityandevservice.domain.route.RouteLineString;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import com.navio.mobilityandevservice.domain.route.RouteStepResponse;
import com.navio.mobilityandevservice.domain.route.TransitLineResponse;
import com.navio.mobilityandevservice.domain.route.TransitJourneyResponse;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.provider.RouteLeg;
import com.navio.mobilityandevservice.provider.RoutePlan;
import com.navio.mobilityandevservice.provider.RouteProvider;
import com.navio.mobilityandevservice.provider.RouteQueryOptions;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.time.Duration;
import java.time.ZoneId;
import java.time.DateTimeException;

@Component
public class GoogleRoutesAdapter implements RouteProvider {

    private static final Set<String> RAIL_VEHICLE_TYPES = Set.of(
            "SUBWAY", "RAIL", "HEAVY_RAIL", "COMMUTER_TRAIN", "HIGH_SPEED_TRAIN",
            "LONG_DISTANCE_TRAIN", "METRO_RAIL", "MONORAIL", "TRAM"
    );

    private static final Set<String> BUS_VEHICLE_TYPES = Set.of("BUS", "INTERCITY_BUS", "TROLLEYBUS");

    private static final String ROUTE_FIELD_MASK = String.join(",",
            "routes.distanceMeters",
            "routes.duration",
            "routes.polyline.encodedPolyline",
            "routes.legs.distanceMeters",
            "routes.legs.duration",
            "routes.legs.polyline.encodedPolyline"
    );

    // Steps are only worth their payload for transit, where they carry the line and its colour.
    private static final String TRANSIT_FIELD_MASK = String.join(",",
            ROUTE_FIELD_MASK,
            "routes.legs.steps.travelMode",
            "routes.legs.steps.distanceMeters",
            "routes.legs.steps.staticDuration",
            "routes.legs.steps.polyline.encodedPolyline",
            "routes.legs.steps.transitDetails",
            "routes.travelAdvisory.transitFare"
    );

    private final RestClient restClient;

    public GoogleRoutesAdapter(@Qualifier("googleRoutesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points) {
        return computeRoute(profile, points, RouteQueryOptions.DEFAULT);
    }

    @Override
    public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points, RouteQueryOptions options) {
        if (points.size() < 2) {
            throw new IllegalArgumentException("At least two route points are required");
        }
        boolean transit = profile == RouteProfile.TRANSIT;
        boolean railOnly = transit && (points.getLast().arrivalMode() == LegTravelMode.RAIL
                || (points.getLast().arrivalMode() != LegTravelMode.BUS && Boolean.TRUE.equals(points.getLast().railOnly())));
        boolean busOnly = transit && points.getLast().arrivalMode() == LegTravelMode.BUS;
        Set<String> allowedVehicles = railOnly ? RAIL_VEHICLE_TYPES : busOnly ? BUS_VEHICLE_TYPES : null;
        if (transit && points.size() != 2) {
            // Google Routes rejects intermediate waypoints in transit mode.
            throw new IllegalArgumentException("A transit route connects exactly two points");
        }

        GoogleRouteRequest request = new GoogleRouteRequest(
                waypoint(points.getFirst()),
                waypoint(points.getLast()),
                transit ? null : points.subList(1, points.size() - 1).stream().map(this::waypoint).toList(),
                travelMode(profile),
                profile == RouteProfile.DRIVING_TRAFFIC ? "TRAFFIC_AWARE" : null,
                "HIGH_QUALITY",
                "ENCODED_POLYLINE",
                railOnly || busOnly || (transit && options.includeTransitAlternatives()),
                transit && (allowedVehicles != null || options.transitRoutingPreference() != null)
                        ? new GoogleTransitPreferences(railOnly ? List.of("RAIL") : busOnly ? List.of("BUS") : null,
                            options.transitRoutingPreference() == null ? null : options.transitRoutingPreference().name()) : null,
                transit ? options.departureTime() : null
        );

        try {
            GoogleRoutesResponse response = restClient.post()
                    .uri("")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("X-Goog-FieldMask", transit ? TRANSIT_FIELD_MASK : ROUTE_FIELD_MASK)
                    .body(request)
                    .retrieve()
                    .body(GoogleRoutesResponse.class);

            if (response == null || response.routes() == null || response.routes().isEmpty()) {
                if (transit) {
                    // No transit service between these points is an answer, not a provider failure;
                    // throwing here would count against the circuit breaker shared with driving.
                    return new RoutePlan(List.of());
                }
                throw new ProviderClientException("Google Routes returned no route");
            }

            // Google treats allowedTravelModes as a preference. Check every ride, including transfers.
            GoogleRoute route = allowedVehicles != null
                    ? response.routes().stream().filter(candidate -> isVehicleRoute(candidate, allowedVehicles)).findFirst().orElse(null)
                    : response.routes().getFirst();
            if (route == null) {
                return new RoutePlan(List.of());
            }
            List<RouteLeg> legs = safeList(route.legs()).stream()
                    .map(leg -> transit ? mapTransitLeg(leg, route.polyline()) : mapLeg(leg))
                    .toList();

            if (legs.isEmpty()) {
                throw new ProviderClientException("Google Routes returned no route legs");
            }

            List<TransitJourneyResponse> journeys = new ArrayList<>();
            if (transit && options.includeTransitAlternatives()) {
                for (GoogleRoute candidate : response.routes()) {
                    if (journeys.size() >= 4) break;
                    if (allowedVehicles != null && !isVehicleRoute(candidate, allowedVehicles)) continue;
                    TransitJourneyResponse journey = mapJourney(candidate, journeys.size());
                    if (journey != null) journeys.add(journey);
                }
            }
            return new RoutePlan(legs, journeys);
        } catch (ProviderClientException exception) {
            throw exception;
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ProviderClientException("Google route calculation failed", exception);
        }
    }

    private RouteLeg mapLeg(GoogleRouteLeg leg) {
        return new RouteLeg(
                decode(leg.polyline()),
                leg.distanceMeters() == null ? 0 : leg.distanceMeters(),
                parseDurationSeconds(leg.duration())
        );
    }

    private boolean isVehicleRoute(GoogleRoute route, Set<String> allowedVehicles) {
        List<GoogleRouteLeg> legs = safeList(route.legs());
        if (legs.size() != 1) {
            return false;
        }
        boolean hasRailRide = false;
        for (GoogleRouteStep step : safeList(legs.getFirst().steps())) {
            if ("WALK".equals(step.travelMode())) {
                continue;
            }
            GoogleTransitDetails details = step.transitDetails();
            GoogleTransitLine line = details == null ? null : details.transitLine();
            GoogleTransitVehicle vehicle = line == null ? null : line.vehicle();
            if (!"TRANSIT".equals(step.travelMode()) || vehicle == null
                    || vehicle.type() == null || !allowedVehicles.contains(vehicle.type())) {
                return false;
            }
            hasRailRide = true;
        }
        return hasRailRide;
    }

    /**
     * Maps a transit leg. Its duration is the time spent riding and walking, not the leg's
     * door-to-door duration: that includes the wait for the first departure after "now", which
     * says nothing about the trip being planned (at night it can be many hours).
     */
    private RouteLeg mapTransitLeg(GoogleRouteLeg leg) {
        List<RouteStepResponse> steps = mergeWalks(safeList(leg.steps()).stream()
                .map(this::mapStep)
                .toList());
        long movingSeconds = steps.stream()
                .mapToLong(step -> step.durationSeconds() == null ? 0 : step.durationSeconds())
                .sum();
        return new RouteLeg(
                decode(leg.polyline()),
                leg.distanceMeters() == null ? 0 : leg.distanceMeters(),
                steps.isEmpty() ? parseDurationSeconds(leg.duration()) : movingSeconds,
                steps
        );
    }

    private RouteLeg mapTransitLeg(GoogleRouteLeg source, GooglePolyline routePolyline) {
        RouteLeg leg = mapTransitLeg(source);
        List<RouteCoordinate> geometry = leg.coordinates();
        if (geometry.size() < 2) geometry = decode(routePolyline);
        if (geometry.size() < 2) geometry = leg.steps().stream()
                .flatMap(step -> step.geometry().coordinates().stream()).toList();
        return new RouteLeg(geometry, leg.distanceMeters(), leg.durationSeconds(), leg.steps());
    }

    private RouteStepResponse mapStep(GoogleRouteStep step) {
        GoogleTransitDetails details = step.transitDetails();
        boolean ride = "TRANSIT".equals(step.travelMode()) && details != null;
        return new RouteStepResponse(
                ride ? LegTravelMode.TRANSIT : LegTravelMode.WALK,
                new RouteLineString(decode(step.polyline())),
                step.distanceMeters(),
                parseDurationSeconds(step.staticDuration()),
                ride ? mapLine(details.transitLine()) : null,
                ride ? stopName(details.stopDetails(), true) : null,
                ride ? stopName(details.stopDetails(), false) : null,
                ride ? details.stopCount() : null,
                ride ? parseInstant(details.stopDetails() == null ? null : details.stopDetails().departureTime()) : null,
                ride ? parseInstant(details.stopDetails() == null ? null : details.stopDetails().arrivalTime()) : null,
                ride ? details.headsign() : null,
                ride ? stopLocation(details.stopDetails() == null ? null : details.stopDetails().departureStop()) : null,
                ride ? stopLocation(details.stopDetails() == null ? null : details.stopDetails().arrivalStop()) : null
        );
    }

    private TransitJourneyResponse mapJourney(GoogleRoute route, int index) {
        if (safeList(route.legs()).size() != 1) return null;
        GoogleRouteLeg source = route.legs().getFirst();
        RouteLeg leg = mapTransitLeg(source);
        List<RouteStepResponse> steps = leg.steps();
        List<RouteStepResponse> rides = steps.stream().filter(step -> step.travelMode() == LegTravelMode.TRANSIT).toList();
        if (rides.isEmpty()) return null;
        List<RouteCoordinate> geometry = leg.coordinates();
        if (geometry.size() < 2) geometry = decode(route.polyline());
        if (geometry.size() < 2) geometry = steps.stream().flatMap(step -> step.geometry().coordinates().stream()).toList();
        if (geometry.size() < 2) return null;
        long walkingSeconds = steps.stream().filter(step -> step.travelMode() == LegTravelMode.WALK)
                .mapToLong(step -> step.durationSeconds() == null ? 0 : step.durationSeconds()).sum();
        Instant departure = rides.getFirst().departureTime();
        Instant arrival = rides.getLast().arrivalTime();
        long access = 0, egress = 0;
        for (RouteStepResponse step : steps) {
            if (step.travelMode() == LegTravelMode.TRANSIT) break;
            access += step.durationSeconds() == null ? 0 : step.durationSeconds();
        }
        for (int position = steps.size() - 1; position >= 0; position--) {
            RouteStepResponse step = steps.get(position);
            if (step.travelMode() == LegTravelMode.TRANSIT) break;
            egress += step.durationSeconds() == null ? 0 : step.durationSeconds();
        }
        if (departure != null) departure = departure.minusSeconds(access);
        if (arrival != null) arrival = arrival.plusSeconds(egress);
        Long duration = departure != null && arrival != null && !arrival.isBefore(departure)
                ? Duration.between(departure, arrival).getSeconds()
                : source.duration() == null ? null : parseDurationSeconds(source.duration());
        String timeZone = null;
        for (GoogleRouteStep step : safeList(source.steps())) {
            GoogleTransitDetails details = step.transitDetails();
            if (details != null && details.localizedValues() != null && details.localizedValues().departureTime() != null) {
                try {
                    timeZone = ZoneId.of(details.localizedValues().departureTime().timeZone()).getId();
                } catch (DateTimeException | NullPointerException ignored) { }
                if (timeZone != null) break;
            }
        }
        GoogleMoney money = route.travelAdvisory() == null ? null : route.travelAdvisory().transitFare();
        BigDecimal fare = null;
        String currency = null;
        if (money != null && money.currencyCode() != null && money.currencyCode().matches("[A-Z]{3}")) {
            try {
                fare = new BigDecimal(money.units() == null ? "0" : money.units())
                        .add(BigDecimal.valueOf(money.nanos() == null ? 0 : money.nanos(), 9));
                if (fare.signum() < 0) fare = null;
                else currency = money.currencyCode();
            } catch (NumberFormatException ignored) { }
        }
        return new TransitJourneyResponse("google-" + index, "GOOGLE", new RouteLineString(geometry),
                source.distanceMeters(), duration, walkingSeconds, departure, arrival, timeZone, fare, currency, steps);
    }

    private Instant parseInstant(String value) {
        try { return value == null ? null : Instant.parse(value); }
        catch (DateTimeException ignored) { return null; }
    }

    private RouteCoordinate stopLocation(GoogleTransitStop stop) {
        GoogleLatLng point = stop == null || stop.location() == null ? null : stop.location().latLng();
        return point == null ? null : new RouteCoordinate(point.longitude(), point.latitude());
    }

    private TransitLineResponse mapLine(GoogleTransitLine line) {
        if (line == null) {
            return new TransitLineResponse(null, null, null, null, null);
        }
        return new TransitLineResponse(
                line.name(),
                line.nameShort(),
                hexColor(line.color()),
                hexColor(line.textColor()),
                line.vehicle() == null ? null : line.vehicle().type()
        );
    }

    /** Google splits a walk into one step per turn; the planner only needs each walk once. */
    private static List<RouteStepResponse> mergeWalks(List<RouteStepResponse> steps) {
        List<RouteStepResponse> merged = new ArrayList<>();
        for (RouteStepResponse step : steps) {
            RouteStepResponse previous = merged.isEmpty() ? null : merged.getLast();
            if (previous != null
                    && previous.travelMode() == LegTravelMode.WALK
                    && step.travelMode() == LegTravelMode.WALK) {
                List<RouteCoordinate> coordinates = new ArrayList<>(previous.geometry().coordinates());
                coordinates.addAll(step.geometry().coordinates());
                merged.set(merged.size() - 1, new RouteStepResponse(
                        LegTravelMode.WALK,
                        new RouteLineString(coordinates),
                        sum(previous.distanceMeters(), step.distanceMeters()),
                        sum(previous.durationSeconds(), step.durationSeconds()),
                        null, null, null, null));
            } else {
                merged.add(step);
            }
        }
        return merged;
    }

    private static Long sum(Long first, Long second) {
        return (first == null ? 0 : first) + (second == null ? 0 : second);
    }

    private static String stopName(GoogleStopDetails stopDetails, boolean departure) {
        if (stopDetails == null) {
            return null;
        }
        GoogleTransitStop stop = departure ? stopDetails.departureStop() : stopDetails.arrivalStop();
        return stop == null ? null : stop.name();
    }

    /** Accepts only {@code #rgb}/{@code #rrggbb}, so a provider value can never inject style text. */
    private static String hexColor(String value) {
        return value != null && value.matches("#(?:[0-9a-fA-F]{3}){1,2}") ? value.toLowerCase() : null;
    }

    private static List<RouteCoordinate> decode(GooglePolyline polyline) {
        return EncodedPolylineDecoder.decode(polyline == null ? null : polyline.encodedPolyline());
    }

    private GoogleWaypoint waypoint(RoutePointRequest point) {
        return new GoogleWaypoint(new GoogleLocation(new GoogleLatLng(point.lat(), point.lng())));
    }

    private String travelMode(RouteProfile profile) {
        return switch (profile) {
            case DRIVING, DRIVING_TRAFFIC -> "DRIVE";
            case WALKING -> "WALK";
            case CYCLING -> "BICYCLE";
            case TRANSIT -> "TRANSIT";
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
            boolean computeAlternativeRoutes,
            GoogleTransitPreferences transitPreferences,
            Instant departureTime
    ) {
    }

    private record GoogleTransitPreferences(List<String> allowedTravelModes, String routingPreference) {
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
            List<GoogleRouteLeg> legs,
            GoogleTravelAdvisory travelAdvisory
    ) {
    }

    private record GoogleRouteLeg(
            Long distanceMeters,
            String duration,
            GooglePolyline polyline,
            List<GoogleRouteStep> steps
    ) {
    }

    private record GoogleRouteStep(
            String travelMode,
            Long distanceMeters,
            String staticDuration,
            GooglePolyline polyline,
            GoogleTransitDetails transitDetails
    ) {
    }

    private record GoogleTransitDetails(
            GoogleStopDetails stopDetails,
            GoogleTransitLine transitLine,
            Integer stopCount,
            String headsign,
            GoogleLocalizedValues localizedValues
    ) {
    }

    private record GoogleStopDetails(GoogleTransitStop departureStop, GoogleTransitStop arrivalStop,
                                     String departureTime, String arrivalTime) {
    }

    private record GoogleTransitStop(String name, GoogleLocation location) {
    }

    private record GoogleTransitLine(
            String name,
            String nameShort,
            String color,
            String textColor,
            GoogleTransitVehicle vehicle
    ) {
    }

    private record GoogleTransitVehicle(String type) {
    }

    private record GooglePolyline(String encodedPolyline) {
    }

    private record GoogleTravelAdvisory(GoogleMoney transitFare) { }
    private record GoogleMoney(String currencyCode, String units, Integer nanos) { }
    private record GoogleLocalizedValues(GoogleLocalizedTime departureTime) { }
    private record GoogleLocalizedTime(String timeZone) { }
}
