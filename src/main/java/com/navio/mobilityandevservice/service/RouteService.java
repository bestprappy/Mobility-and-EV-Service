package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.cache.StaleAwareCache;
import com.navio.mobilityandevservice.domain.route.DirectionsRequest;
import com.navio.mobilityandevservice.domain.route.DirectionsResponse;
import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import com.navio.mobilityandevservice.domain.route.RouteLineString;
import com.navio.mobilityandevservice.domain.route.RoutePointGroupRequest;
import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RouteProfile;
import com.navio.mobilityandevservice.domain.route.RouteSegmentResponse;
import com.navio.mobilityandevservice.domain.route.RouteSegmentStatus;
import com.navio.mobilityandevservice.provider.RouteLeg;
import com.navio.mobilityandevservice.provider.RoutePlan;
import com.navio.mobilityandevservice.provider.RouteProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Service
public class RouteService {

    private static final String CIRCUIT_BREAKER_NAME = "googleRoutes";
    private static final String FALLBACK_REASON = "Road route is temporarily unavailable";
    private static final double EARTH_RADIUS_METERS = 6_371_000;

    private final RouteProvider routeProvider;
    private final CircuitBreaker circuitBreaker;
    private final StaleAwareCache<RoutePlan> routePlanCache;

    public RouteService(
            RouteProvider routeProvider,
            CircuitBreakerFactory<?, ?> circuitBreakerFactory,
            @Qualifier("routePlanCache") StaleAwareCache<RoutePlan> routePlanCache
    ) {
        this.routeProvider = routeProvider;
        this.circuitBreaker = circuitBreakerFactory.create(CIRCUIT_BREAKER_NAME);
        this.routePlanCache = routePlanCache;
    }

    public DirectionsResponse computeDirections(DirectionsRequest request) {
        RouteProfile profile = request.effectiveProfile();
        List<RouteSegmentResponse> segments = new ArrayList<>();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<RouteSegmentResponse>>> routeTasks = request.groups().stream()
                    .map(group -> executor.submit(() -> {
                        RoutePlan routePlan = resolveRoutePlan(profile, group.points());
                        return toSegments(group, routePlan);
                    }))
                    .toList();

            for (Future<List<RouteSegmentResponse>> routeTask : routeTasks) {
                segments.addAll(routeTask.get());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Route calculation was interrupted", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("Route calculation failed unexpectedly", exception.getCause());
        }

        return new DirectionsResponse(segments);
    }

    private RoutePlan resolveRoutePlan(RouteProfile profile, List<RoutePointRequest> points) {
        String cacheKey = routeCacheKey(profile, points);

        return routePlanCache.getFresh(cacheKey).orElseGet(() -> circuitBreaker.run(
                () -> {
                    RoutePlan routePlan = routeProvider.computeRoute(profile, points);
                    routePlanCache.put(cacheKey, routePlan);
                    return routePlan;
                },
                throwable -> routePlanCache.getStale(cacheKey).orElse(null)
        ));
    }

    private List<RouteSegmentResponse> toSegments(
            RoutePointGroupRequest group,
            RoutePlan routePlan
    ) {
        List<RouteSegmentResponse> segments = new ArrayList<>();

        for (int index = 0; index < group.points().size() - 1; index++) {
            RoutePointRequest from = group.points().get(index);
            RoutePointRequest to = group.points().get(index + 1);
            RouteLeg leg = routePlan != null && routePlan.legs().size() > index
                    ? routePlan.legs().get(index)
                    : null;

            if (leg == null || leg.coordinates().size() < 2) {
                segments.add(fallbackSegment(group.blockId(), from, to));
            } else {
                segments.add(routedSegment(group.blockId(), from, to, leg));
            }
        }

        return segments;
    }

    private RouteSegmentResponse routedSegment(
            String blockId,
            RoutePointRequest from,
            RoutePointRequest to,
            RouteLeg leg
    ) {
        return new RouteSegmentResponse(
                segmentId(blockId, from.id(), to.id()),
                blockId,
                from.id(),
                to.id(),
                from.name(),
                to.name(),
                RouteSegmentStatus.ROUTED,
                new RouteLineString(leg.coordinates()),
                leg.distanceMeters(),
                leg.durationSeconds(),
                null
        );
    }

    private RouteSegmentResponse fallbackSegment(
            String blockId,
            RoutePointRequest from,
            RoutePointRequest to
    ) {
        return new RouteSegmentResponse(
                segmentId(blockId, from.id(), to.id()),
                blockId,
                from.id(),
                to.id(),
                from.name(),
                to.name(),
                RouteSegmentStatus.FALLBACK,
                new RouteLineString(List.of(
                        new RouteCoordinate(from.lng(), from.lat()),
                        new RouteCoordinate(to.lng(), to.lat())
                )),
                Math.round(directDistanceMeters(from, to)),
                null,
                FALLBACK_REASON
        );
    }

    private String routeCacheKey(RouteProfile profile, List<RoutePointRequest> points) {
        StringBuilder key = new StringBuilder(profile.value());
        for (RoutePointRequest point : points) {
            key.append(':')
                    .append(coordinateKey(point.lat()))
                    .append(',')
                    .append(coordinateKey(point.lng()));
        }
        return key.toString();
    }

    private String coordinateKey(double coordinate) {
        return BigDecimal.valueOf(coordinate)
                .setScale(5, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString();
    }

    private String segmentId(String blockId, String fromItemId, String toItemId) {
        return String.format(Locale.ROOT, "segment-%s-%s-%s", blockId, fromItemId, toItemId);
    }

    private double directDistanceMeters(RoutePointRequest from, RoutePointRequest to) {
        double fromLat = Math.toRadians(from.lat());
        double toLat = Math.toRadians(to.lat());
        double deltaLat = Math.toRadians(to.lat() - from.lat());
        double deltaLng = Math.toRadians(to.lng() - from.lng());
        double haversine = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(fromLat) * Math.cos(toLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }
}
