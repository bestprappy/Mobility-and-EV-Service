package com.navio.mobilityandevservice.provider.otp;

import com.navio.mobilityandevservice.config.OpenTripPlannerProperties;
import com.navio.mobilityandevservice.domain.route.*;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.provider.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** OTP 2.10 GTFS GraphQL API. The graph, not a country allowlist, determines coverage. */
@Component
public class OpenTripPlannerRoutesAdapter implements RouteProvider {
    private static final Set<String> RAIL = Set.of("RAIL", "SUBWAY", "TRAM", "MONORAIL");
    private static final Set<String> BUS = Set.of("BUS", "COACH", "TROLLEYBUS");
    private static final Set<String> PUBLIC_MODES = Set.of("RAIL", "SUBWAY", "TRAM", "MONORAIL", "BUS", "COACH",
            "TROLLEYBUS", "FERRY", "CABLE_CAR", "FUNICULAR", "GONDOLA");
    private static final String QUERY = """
            query NavioTransit($origin: PlanLabeledLocationInput!, $destination: PlanLabeledLocationInput!,
                $dateTime: PlanDateTimeInput!, $modes: PlanModesInput!, $preferences: PlanPreferencesInput!) {
              planConnection(origin: $origin, destination: $destination, dateTime: $dateTime,
                  modes: $modes, preferences: $preferences, first: 4, searchWindow: "PT1H") {
                edges { node {
                  duration start end
                  legs {
                    mode transitLeg distance duration headsign
                    start { scheduledTime estimated { time } }
                    end { scheduledTime estimated { time } }
                    from { name lat lon } to { name lat lon }
                    route { longName shortName color textColor agency { timezone } }
                    legGeometry { points }
                    stopCalls { stopLocation { __typename } }
                  }
                } }
              }
            }
            """;
    private final RestClient client;
    private final OpenTripPlannerProperties properties;
    private volatile Set<String> availableModes;

    public OpenTripPlannerRoutesAdapter(@Qualifier("openTripPlannerRestClient") RestClient client,
                                       OpenTripPlannerProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    @Override
    public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points) {
        return computeRoute(profile, points, RouteQueryOptions.DEFAULT);
    }

    @Override
    public RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points, RouteQueryOptions options) {
        if (profile != RouteProfile.TRANSIT || points.size() != 2) {
            throw new IllegalArgumentException("OTP transit routing connects exactly two points");
        }
        if (!properties.enabled()) return new RoutePlan(List.of());
        RoutePointRequest arrival = points.getLast();
        Set<String> allowed = arrival.arrivalMode() == LegTravelMode.BUS ? BUS
                : arrival.arrivalMode() == LegTravelMode.RAIL || Boolean.TRUE.equals(arrival.railOnly()) ? RAIL : null;
        Set<String> requested = allowed == null ? PUBLIC_MODES : allowed;
        Instant departure = options.departureTime() == null ? Instant.now() : options.departureTime();
        Map<String, Object> preferences = options.transitRoutingPreference() == null ? Map.of()
                : switch (options.transitRoutingPreference()) {
                    case FEWER_TRANSFERS -> Map.of("transit", Map.of("transfer", Map.of("cost", 300)));
                    case LESS_WALKING -> Map.of("street", Map.of("walk", Map.of("reluctance", 4.0)));
                };
        try {
            // A loaded rail-only graph cannot answer bus searches. Detect that from its inventory,
            // rather than let an unsupported mode count as a provider outage or poison the breaker.
            List<Map<String, String>> modes = graphModes().stream().filter(requested::contains).sorted()
                    .map(mode -> Map.of("mode", mode)).toList();
            if (modes.isEmpty()) return new RoutePlan(List.of());
            Map<String, Object> variables = Map.of("origin", location(points.getFirst()),
                    "destination", location(arrival), "dateTime", Map.of("earliestDeparture", departure.toString()),
                    "modes", Map.of("transitOnly", true, "transit", Map.of("transit", modes)), "preferences", preferences);
            Response response = client.post().uri("").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("query", QUERY, "variables", variables)).retrieve().body(Response.class);
            if (response == null || !safe(response.errors()).isEmpty() || response.data() == null
                    || response.data().planConnection() == null) {
                throw new ProviderClientException("OpenTripPlanner returned an invalid routing response");
            }
            List<TransitJourneyResponse> journeys = new ArrayList<>();
            for (Edge edge : safe(response.data().planConnection().edges())) {
                if (edge == null || edge.node() == null) continue;
                TransitJourneyResponse journey = journey(edge.node(), allowed, journeys.size());
                if (journey != null) journeys.add(journey);
                if (journeys.size() == 4) break;
            }
            if (journeys.isEmpty()) return new RoutePlan(List.of());
            TransitJourneyResponse first = journeys.getFirst();
            long moving = first.steps().stream().mapToLong(step -> step.durationSeconds()).sum();
            return new RoutePlan(List.of(new RouteLeg(first.geometry().coordinates(), first.distanceMeters(), moving, first.steps())),
                    options.includeTransitAlternatives() ? journeys : List.of(first));
        } catch (RestClientException | IllegalArgumentException exception) {
            throw new ProviderClientException("OpenTripPlanner route calculation failed", exception);
        }
    }

    private Set<String> graphModes() {
        Set<String> cached = availableModes;
        if (cached != null) return cached;
        synchronized (this) {
            if (availableModes == null) {
                Response response = client.post().uri("").contentType(MediaType.APPLICATION_JSON)
                        .body(Map.of("query", "{ routes { mode } }", "variables", Map.of()))
                        .retrieve().body(Response.class);
                if (response == null || !safe(response.errors()).isEmpty() || response.data() == null
                        || response.data().routes() == null) {
                    throw new ProviderClientException("OpenTripPlanner network inventory is unavailable");
                }
                availableModes = response.data().routes().stream().filter(java.util.Objects::nonNull)
                        .map(RouteMode::mode).filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
            return availableModes;
        }
    }

    private TransitJourneyResponse journey(Itinerary itinerary, Set<String> allowed, int index) {
        List<RouteStepResponse> steps = new ArrayList<>();
        List<RouteCoordinate> geometry = new ArrayList<>();
        String timeZone = null;
        boolean hasRide = false;
        for (Leg leg : safe(itinerary.legs())) {
            if (leg == null || leg.mode() == null || leg.distance() == null || leg.duration() == null
                    || !Double.isFinite(leg.distance()) || !Double.isFinite(leg.duration())
                    || leg.distance() < 0 || leg.duration() < 0) return null;
            boolean ride = Boolean.TRUE.equals(leg.transitLeg());
            if (!ride && !"WALK".equals(leg.mode())) return null;
            if (ride && (allowed != null && !allowed.contains(leg.mode()))) return null;
            List<RouteCoordinate> coordinates = EncodedPolylineDecoder.decode(leg.legGeometry() == null ? null : leg.legGeometry().points());
            if (coordinates.stream().anyMatch(point -> !Double.isFinite(point.lat()) || !Double.isFinite(point.lng())
                    || Math.abs(point.lat()) > 90 || Math.abs(point.lng()) > 180)) return null;
            if (coordinates.size() < 2) {
                if (!ride && leg.distance() == 0 && leg.duration() == 0) continue;
                return null; // Do not substitute straight lines for an unconfirmed street/rail shape.
            }
            if (ride && (leg.route() == null || !validPlace(leg.from()) || !validPlace(leg.to())
                    || leg.from().name() == null || leg.to().name() == null)) return null;
            Instant start = time(leg.start());
            Instant end = time(leg.end());
            if (ride && (start == null || end == null || end.isBefore(start))) return null;
            steps.add(new RouteStepResponse(ride ? LegTravelMode.TRANSIT : LegTravelMode.WALK,
                    new RouteLineString(coordinates), Math.round(leg.distance()), Math.round(leg.duration()),
                    ride ? line(leg) : null, ride ? leg.from().name() : null, ride ? leg.to().name() : null,
                    ride && leg.stopCalls() != null ? Math.max(0, leg.stopCalls().size() - 1) : null,
                    ride ? start : null, ride ? end : null, ride ? leg.headsign() : null,
                    ride ? coordinate(leg.from()) : null, ride ? coordinate(leg.to()) : null));
            geometry.addAll(coordinates);
            if (ride) {
                hasRide = true;
                if (timeZone == null && leg.route().agency() != null) {
                    try { timeZone = ZoneId.of(leg.route().agency().timezone()).getId(); }
                    catch (DateTimeException | NullPointerException ignored) { }
                }
            }
        }
        if (!hasRide || geometry.size() < 2) return null;
        Instant departure = instant(itinerary.start());
        Instant arrival = instant(itinerary.end());
        if (departure == null || arrival == null || arrival.isBefore(departure)) return null;
        long walking = steps.stream().filter(step -> step.travelMode() == LegTravelMode.WALK)
                .mapToLong(step -> step.durationSeconds()).sum();
        long distance = steps.stream().mapToLong(step -> step.distanceMeters()).sum();
        return new TransitJourneyResponse("otp-" + properties.networkId() + "-" + index, "OTP",
                new RouteLineString(geometry), distance, java.time.Duration.between(departure, arrival).getSeconds(),
                walking, departure, arrival, timeZone, null, null, steps);
    }

    private static Map<String, Object> location(RoutePointRequest point) {
        return Map.of("location", Map.of("coordinate", Map.of("latitude", point.lat(), "longitude", point.lng())));
    }

    private static TransitLineResponse line(Leg leg) {
        String vehicle = switch (leg.mode()) {
            case "RAIL" -> "HEAVY_RAIL";
            case "COACH" -> "INTERCITY_BUS";
            default -> leg.mode();
        };
        return new TransitLineResponse(leg.route().longName(), leg.route().shortName(),
                color(leg.route().color()), color(leg.route().textColor()), vehicle);
    }

    private static String color(String value) {
        if (value == null) return null;
        String normalized = value.startsWith("#") ? value : "#" + value;
        return normalized.matches("#[0-9a-fA-F]{6}") ? normalized.toLowerCase(java.util.Locale.ROOT) : null;
    }

    private static boolean validPlace(Place place) {
        return place != null && place.lat() != null && place.lon() != null && Double.isFinite(place.lat())
                && Double.isFinite(place.lon()) && Math.abs(place.lat()) <= 90 && Math.abs(place.lon()) <= 180;
    }

    private static RouteCoordinate coordinate(Place place) { return new RouteCoordinate(place.lon(), place.lat()); }
    private static Instant time(LegTime time) {
        if (time == null) return null;
        Instant estimate = time.estimated() == null ? null : instant(time.estimated().time());
        return estimate == null ? instant(time.scheduledTime()) : estimate;
    }
    private static Instant instant(String value) {
        try { return value == null ? null : OffsetDateTime.parse(value).toInstant(); }
        catch (DateTimeException ignored) { return null; }
    }
    private static <T> List<T> safe(List<T> values) { return values == null ? List.of() : values; }

    private record Response(Data data, List<Error> errors) { }
    private record Error(String message) { }
    private record Data(Connection planConnection, List<RouteMode> routes) { }
    private record RouteMode(String mode) { }
    private record Connection(List<Edge> edges) { }
    private record Edge(Itinerary node) { }
    private record Itinerary(Long duration, String start, String end, List<Leg> legs) { }
    private record Leg(String mode, Boolean transitLeg, Double distance, Double duration, String headsign,
                       LegTime start, LegTime end, Place from, Place to, Route route,
                       Geometry legGeometry, List<StopCall> stopCalls) { }
    private record LegTime(String scheduledTime, Estimate estimated) { }
    private record Estimate(String time) { }
    private record Place(String name, Double lat, Double lon) { }
    private record Route(String longName, String shortName, String color, String textColor, Agency agency) { }
    private record Agency(String timezone) { }
    private record Geometry(String points) { }
    private record StopCall(Map<String, String> stopLocation) { }
}
