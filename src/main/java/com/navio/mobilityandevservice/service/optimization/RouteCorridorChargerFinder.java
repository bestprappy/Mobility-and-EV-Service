package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvChargerStatus;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.optimization.EvRouteStopRequest;
import com.navio.mobilityandevservice.domain.route.RouteCoordinate;
import com.navio.mobilityandevservice.service.EvChargerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Component
@Slf4j
public class RouteCorridorChargerFinder {

    private static final double SEARCH_RADIUS_KM = 15;
    private static final double SAMPLE_INTERVAL_KM = 30;
    private static final int MAX_SEARCH_ANCHORS = 48;
    private static final int MAX_CANDIDATES_PER_SPAN = 40;
    private static final double EARTH_RADIUS_KM = 6_371.0;

    private final EvChargerService evChargerService;

    public RouteCorridorChargerFinder(EvChargerService evChargerService) {
        this.evChargerService = evChargerService;
    }

    OptimizationRoute populateCandidates(
            OptimizationRoute route,
            EvRouteOptimizationRequest request,
            List<EvRouteStopRequest> replaceableStops
    ) {
        List<SearchAnchor> anchors = buildAnchors(route.spans());
        Map<Integer, List<EvChargerResponse>> liveChargersBySpan = searchAnchors(anchors);
        Set<String> seenLiveChargerIds = liveChargersBySpan.values().stream()
                .flatMap(List::stream)
                .map(EvChargerResponse::id)
                .collect(HashSet::new, Set::add, Set::addAll);
        Map<String, String> existingItemIdsByChargerId = replaceableStops.stream()
                .filter(EvRouteStopRequest::chargerStop)
                .collect(HashMap::new, (map, stop) -> map.put(stop.charger().id(), stop.itemId()), Map::putAll);

        List<OptimizationRouteSpan> populatedSpans = route.spans().stream()
                .map(span -> span.withCandidates(mapLiveCandidates(
                        span,
                        liveChargersBySpan.getOrDefault(span.index(), List.of()),
                        existingItemIdsByChargerId,
                        request
                )))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        for (EvRouteStopRequest existingStop : replaceableStops) {
            if (!existingStop.chargerStop() || seenLiveChargerIds.contains(existingStop.charger().id())) {
                continue;
            }
            addSnapshotCandidate(populatedSpans, existingStop, request);
        }

        List<OptimizationRouteSpan> sortedSpans = populatedSpans.stream()
                .map(span -> span.withCandidates(capCandidates(span).stream()
                        .sorted(Comparator.comparingDouble(OptimizationChargerCandidate::progressKm))
                        .toList()))
                .toList();
        return new OptimizationRoute(sortedSpans);
    }

    /**
     * Caps how many candidates one span hands to the optimizer. The search relaxes every node pair
     * at every reachable state of charge, so an uncapped corridor through a dense city turns a
     * sub-second request into a multi-second one. Candidates already in the trip are always kept,
     * and the rest are thinned by keeping the strongest charger in each slice of the corridor so
     * coverage stays even instead of clustering around one town.
     */
    private List<OptimizationChargerCandidate> capCandidates(OptimizationRouteSpan span) {
        List<OptimizationChargerCandidate> candidates = span.candidates();
        if (candidates.size() <= MAX_CANDIDATES_PER_SPAN) {
            return candidates;
        }

        List<OptimizationChargerCandidate> pinned = new ArrayList<>();
        Map<Integer, OptimizationChargerCandidate> strongestBySlice = new LinkedHashMap<>();
        for (OptimizationChargerCandidate candidate : candidates) {
            if (candidate.existingItemId() != null) {
                pinned.add(candidate);
                continue;
            }
            strongestBySlice.merge(
                    corridorSlice(span, candidate),
                    candidate,
                    (current, next) -> candidateStrength(current) >= candidateStrength(next) ? current : next
            );
        }

        List<OptimizationChargerCandidate> capped = new ArrayList<>(pinned);
        capped.addAll(strongestBySlice.values());
        log.debug(
                "Span {} thinned from {} to {} charger candidates",
                span.index(),
                candidates.size(),
                capped.size()
        );
        return capped;
    }

    private int corridorSlice(OptimizationRouteSpan span, OptimizationChargerCandidate candidate) {
        if (span.distanceKm() <= 0) {
            return 0;
        }
        double ratio = clamp(candidate.progressKm() / span.distanceKm(), 0, 1);
        return Math.min(MAX_CANDIDATES_PER_SPAN - 1, (int) (ratio * MAX_CANDIDATES_PER_SPAN));
    }

    private double candidateStrength(OptimizationChargerCandidate candidate) {
        EvChargerResponse charger = candidate.charger();
        double powerScore = Math.min(charger.maxKw(), 150) / 150.0;
        double availabilityScore = charger.availableConnectors() == null
                ? 0.5
                : Math.min(1, charger.availableConnectors() / (double) Math.max(1, charger.totalConnectors()));
        double detourPenalty = candidate.deviationKm() / Math.max(1, SEARCH_RADIUS_KM);
        return powerScore + charger.confidenceScore() + availabilityScore - detourPenalty;
    }

    private List<OptimizationChargerCandidate> mapLiveCandidates(
            OptimizationRouteSpan span,
            List<EvChargerResponse> chargers,
            Map<String, String> existingItemIdsByChargerId,
            EvRouteOptimizationRequest request
    ) {
        Map<String, OptimizationChargerCandidate> candidatesById = new LinkedHashMap<>();
        for (EvChargerResponse charger : chargers) {
            if (!isUsable(charger, request)) {
                continue;
            }
            RouteProjection projection = project(span, charger.location().lat(), charger.location().lng());
            if (!withinCorridor(span, projection, request.maximumDetourKm())) {
                continue;
            }
            OptimizationChargerCandidate candidate = new OptimizationChargerCandidate(
                    charger,
                    projection.progressKm(),
                    projection.deviationKm(),
                    existingItemIdsByChargerId.get(charger.id())
            );
            candidatesById.merge(
                    charger.id(),
                    candidate,
                    (current, next) -> current.deviationKm() <= next.deviationKm() ? current : next
            );
        }
        return new ArrayList<>(candidatesById.values());
    }

    private void addSnapshotCandidate(
            List<OptimizationRouteSpan> spans,
            EvRouteStopRequest existingStop,
            EvRouteOptimizationRequest request
    ) {
        if (!isUsable(existingStop.charger(), request)) {
            return;
        }
        ProjectedSpan best = spans.stream()
                .map(span -> new ProjectedSpan(span, project(span, existingStop.lat(), existingStop.lng())))
                .min(Comparator.comparingDouble(value -> value.projection().deviationKm()))
                .orElse(null);
        if (best == null || !withinCorridor(best.span(), best.projection(), request.maximumDetourKm())) {
            return;
        }

        int spanListIndex = spans.indexOf(best.span());
        List<OptimizationChargerCandidate> nextCandidates = new ArrayList<>(best.span().candidates());
        nextCandidates.add(new OptimizationChargerCandidate(
                existingStop.charger(),
                best.projection().progressKm(),
                best.projection().deviationKm(),
                existingStop.itemId()
        ));
        spans.set(spanListIndex, best.span().withCandidates(nextCandidates));
    }

    private boolean isUsable(EvChargerResponse charger, EvRouteOptimizationRequest request) {
        return charger.status() == EvChargerStatus.ACTIVE
                && charger.maxKw() > 0
                && charger.connectorTypes().stream().anyMatch(request.vehicle().connectorTypes()::contains);
    }

    private boolean withinCorridor(
            OptimizationRouteSpan span,
            RouteProjection projection,
            double maximumDetourKm
    ) {
        double progressRatio = span.distanceKm() <= 0 ? 0 : projection.progressKm() / span.distanceKm();
        return progressRatio > 0.01
                && progressRatio < 0.99
                && projection.deviationKm() * 2 <= maximumDetourKm;
    }

    private Map<Integer, List<EvChargerResponse>> searchAnchors(List<SearchAnchor> anchors) {
        Map<Integer, List<EvChargerResponse>> chargersBySpan = new HashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<AnchorSearchResult>> futures = limitAnchors(anchors).stream()
                    .map(anchor -> executor.submit(() -> searchAnchor(anchor)))
                    .toList();
            for (Future<AnchorSearchResult> future : futures) {
                AnchorSearchResult result = future.get();
                chargersBySpan.computeIfAbsent(result.spanIndex(), ignored -> new ArrayList<>())
                        .addAll(result.chargers());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("EV route corridor search was interrupted", exception);
        } catch (ExecutionException exception) {
            log.warn("One EV route corridor search failed", exception.getCause());
        }
        return chargersBySpan;
    }

    private AnchorSearchResult searchAnchor(SearchAnchor anchor) {
        try {
            return new AnchorSearchResult(
                    anchor.spanIndex(),
                    evChargerService.nearby(
                            anchor.lat(),
                            anchor.lng(),
                            SEARCH_RADIUS_KM,
                            null,
                            null,
                            false
                    ).items()
            );
        } catch (RuntimeException exception) {
            log.debug(
                    "EV charger corridor anchor failed for span {} at {},{}",
                    anchor.spanIndex(),
                    anchor.lat(),
                    anchor.lng(),
                    exception
            );
            return new AnchorSearchResult(anchor.spanIndex(), List.of());
        }
    }

    private List<SearchAnchor> buildAnchors(List<OptimizationRouteSpan> spans) {
        List<SearchAnchor> anchors = new ArrayList<>();
        for (OptimizationRouteSpan span : spans) {
            anchors.add(new SearchAnchor(span.index(), span.from().lat(), span.from().lng()));
            double distanceToNextSampleKm = SAMPLE_INTERVAL_KM;
            List<RouteCoordinate> geometry = span.geometry();
            for (int index = 1; index < geometry.size(); index++) {
                RouteCoordinate previous = geometry.get(index - 1);
                RouteCoordinate current = geometry.get(index);
                double segmentKm = distanceKm(previous.lat(), previous.lng(), current.lat(), current.lng());
                double traversedSegmentKm = 0;
                double remainingSegmentKm = segmentKm;
                while (remainingSegmentKm >= distanceToNextSampleKm) {
                    traversedSegmentKm += distanceToNextSampleKm;
                    double ratio = traversedSegmentKm / Math.max(segmentKm, 0.001);
                    anchors.add(new SearchAnchor(
                            span.index(),
                            previous.lat() + ratio * (current.lat() - previous.lat()),
                            previous.lng() + ratio * (current.lng() - previous.lng())
                    ));
                    remainingSegmentKm -= distanceToNextSampleKm;
                    distanceToNextSampleKm = SAMPLE_INTERVAL_KM;
                }
                distanceToNextSampleKm -= remainingSegmentKm;
            }
            anchors.add(new SearchAnchor(span.index(), span.to().lat(), span.to().lng()));
        }
        return anchors.stream().distinct().toList();
    }

    private List<SearchAnchor> limitAnchors(List<SearchAnchor> anchors) {
        if (anchors.size() <= MAX_SEARCH_ANCHORS) {
            return anchors;
        }
        List<SearchAnchor> distributed = new ArrayList<>(MAX_SEARCH_ANCHORS);
        for (int index = 0; index < MAX_SEARCH_ANCHORS; index++) {
            int sourceIndex = (int) Math.round(
                    index * (anchors.size() - 1.0) / (MAX_SEARCH_ANCHORS - 1.0)
            );
            distributed.add(anchors.get(sourceIndex));
        }
        return distributed;
    }

    private RouteProjection project(OptimizationRouteSpan span, double lat, double lng) {
        List<RouteCoordinate> geometry = span.geometry();
        if (geometry.size() < 2) {
            return new RouteProjection(0, distanceKm(span.from().lat(), span.from().lng(), lat, lng));
        }

        double geometryLengthKm = 0;
        double bestGeometryProgressKm = 0;
        double bestDeviationKm = Double.MAX_VALUE;
        double traversedKm = 0;
        for (int index = 1; index < geometry.size(); index++) {
            RouteCoordinate from = geometry.get(index - 1);
            RouteCoordinate to = geometry.get(index);
            double segmentKm = distanceKm(from.lat(), from.lng(), to.lat(), to.lng());
            SegmentProjection projection = projectSegment(from, to, lat, lng);
            if (projection.deviationKm() < bestDeviationKm) {
                bestDeviationKm = projection.deviationKm();
                bestGeometryProgressKm = traversedKm + segmentKm * projection.ratio();
            }
            traversedKm += segmentKm;
            geometryLengthKm += segmentKm;
        }
        double roadProgressKm = geometryLengthKm <= 0
                ? 0
                : bestGeometryProgressKm / geometryLengthKm * span.distanceKm();
        return new RouteProjection(roadProgressKm, bestDeviationKm);
    }

    private SegmentProjection projectSegment(RouteCoordinate from, RouteCoordinate to, double lat, double lng) {
        double averageLatRadians = Math.toRadians((from.lat() + to.lat()) / 2);
        double kmPerLat = 110.574;
        double kmPerLng = 111.320 * Math.cos(averageLatRadians);
        double toX = (to.lng() - from.lng()) * kmPerLng;
        double toY = (to.lat() - from.lat()) * kmPerLat;
        double pointX = (lng - from.lng()) * kmPerLng;
        double pointY = (lat - from.lat()) * kmPerLat;
        double lengthSquared = toX * toX + toY * toY;
        double ratio = lengthSquared <= 0 ? 0 : clamp((pointX * toX + pointY * toY) / lengthSquared, 0, 1);
        double deviationKm = Math.hypot(pointX - toX * ratio, pointY - toY * ratio);
        return new SegmentProjection(ratio, deviationKm);
    }

    private double distanceKm(double fromLat, double fromLng, double toLat, double toLng) {
        double deltaLat = Math.toRadians(toLat - fromLat);
        double deltaLng = Math.toRadians(toLng - fromLng);
        double startLat = Math.toRadians(fromLat);
        double endLat = Math.toRadians(toLat);
        double haversine = Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2)
                + Math.cos(startLat) * Math.cos(endLat)
                * Math.sin(deltaLng / 2) * Math.sin(deltaLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(haversine), Math.sqrt(1 - haversine));
    }

    private double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private record SearchAnchor(int spanIndex, double lat, double lng) {
    }

    private record AnchorSearchResult(int spanIndex, List<EvChargerResponse> chargers) {
    }

    private record RouteProjection(double progressKm, double deviationKm) {
    }

    private record SegmentProjection(double ratio, double deviationKm) {
    }

    private record ProjectedSpan(OptimizationRouteSpan span, RouteProjection projection) {
    }
}
