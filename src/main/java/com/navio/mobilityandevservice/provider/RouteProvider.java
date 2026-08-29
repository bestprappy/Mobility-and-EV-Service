package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.domain.route.RoutePointRequest;
import com.navio.mobilityandevservice.domain.route.RouteProfile;

import java.util.List;

public interface RouteProvider {

    RoutePlan computeRoute(RouteProfile profile, List<RoutePointRequest> points);
}
