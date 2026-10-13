package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RouteProfile;

import java.util.List;

public interface RouteProvider {

    default String cacheNamespace(RouteProfile profile) {
        return "google";
    }

    RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points);

    default RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points, RouteQueryOptions options) {
        return computeRoute(profile, points);
    }
}
