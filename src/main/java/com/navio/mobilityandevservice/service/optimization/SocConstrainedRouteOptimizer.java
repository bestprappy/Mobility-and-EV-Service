package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.optimization.EvRouteStopRequest;
import com.navio.mobilityandevservice.domain.optimization.EvVehicleSpec;
import org.springframework.stereotype.Component;
import com.navio.mobilityandevservice.domain.energy.CanonicalEnergy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

@Component
public class SocConstrainedRouteOptimizer {

    private static final Set<EvConnectorType> DC_CONNECTORS = Set.of(
            EvConnectorType.CCS1,
            EvConnectorType.CCS2,
            EvConnectorType.CHADEMO,
            EvConnectorType.NACS,
            EvConnectorType.GB_T
    );
    private static final double EPSILON = 1e-8;
    private static final int MAX_LABELS_PER_NODE = 256;
    private static final int MAX_EXPANSIONS_PER_SPAN = 100_000;
    private static final int MAX_CHARGE_SOC_PCT = 92;
    private static final int CHARGE_BUCKET_SIZE_PCT = 5;
    private static final long CHARGING_STOP_OVERHEAD_SECONDS = 5 * 60L;

    OptimizationSearchResult optimize(OptimizationRoute route, EvRouteOptimizationRequest request) {
        List<SearchLabel> labels = List.of(SearchLabel.start(request.startingSocPct()));
        List<String> warnings = new ArrayList<>();
        if (route.spans().stream().anyMatch(span -> !"ROUTED".equals(span.distanceQuality()) || !span.candidates().isEmpty())) warnings.add("FALLBACK_DISTANCE: approximate distances or charger detours are used; final road verification is not included.");
        if ("RATED_RANGE".equals(request.vehicle().resolvedModel().modelKind())) warnings.add("PROVISIONAL_RATED_RANGE: preview only; automatic charger application is not approved.");

        if (consumedSocPct(1, request.vehicle()) == null) return new OptimizationSearchResult(false, null, 0, 0, List.of(), List.of("Canonical SoC unavailable: suitable energy model and usable capacity are required."));
        for (OptimizationRouteSpan span : route.spans()) {
            if (span.from().observedSocPct() != null) labels = labels.stream().map(label -> label.withSoc(span.from().observedSocPct())).toList();
            try { labels = optimizeSpan(span, request, labels); }
            catch (SearchLimitException limit) { return new OptimizationSearchResult(false, null, 0, 0, List.of(), List.of("Optimizer search limit reached; no verified result is available.")); }
            if (labels.isEmpty()) {
                warnings.add("No safe compatible charging plan was found between "
                        + span.from().name() + " and " + span.to().name() + ".");
                return new OptimizationSearchResult(false, null, 0, 0, List.of(), warnings);
            }
        }

        SearchLabel best = labels.stream().min(Comparator.comparingDouble(SearchLabel::costSeconds)).orElseThrow();
        return new OptimizationSearchResult(
                true,
                route.spans().isEmpty() || route.spans().getLast().to().observedSocPct() == null ? best.socPct() : route.spans().getLast().to().observedSocPct(),
                best.drivingSeconds(),
                best.chargingMinutes(),
                best.chargeStops(),
                warnings
        );
    }

    private List<SearchLabel> optimizeSpan(
            OptimizationRouteSpan span,
            EvRouteOptimizationRequest request,
            List<SearchLabel> incomingLabels
    ) {
        List<RouteNode> nodes = routeNodes(span);
        PriorityQueue<NodeLabel> queue = new PriorityQueue<>(Comparator.comparingDouble(value -> value.label().costSeconds()));
        Map<Integer, List<SearchLabel>> bestByNode = new HashMap<>();
        for (SearchLabel incoming : incomingLabels) admit(0, incoming, bestByNode, queue);
        int expansions = 0;
        while (!queue.isEmpty()) {
            NodeLabel currentNodeLabel = queue.poll();
            int nodeIndex = currentNodeLabel.nodeIndex();
            SearchLabel currentLabel = currentNodeLabel.label();
            if (++expansions > MAX_EXPANSIONS_PER_SPAN) throw new SearchLimitException();
            if (!bestByNode.getOrDefault(nodeIndex, List.of()).contains(currentLabel)) {
                continue;
            }
            if (nodeIndex == nodes.size() - 1) {
                continue;
            }

            RouteNode currentNode = nodes.get(nodeIndex);
            for (DepartureOption departure : departureOptions(currentNode, currentLabel, request)) {
                for (int nextIndex = nodeIndex + 1; nextIndex < nodes.size(); nextIndex++) {
                    RouteNode nextNode = nodes.get(nextIndex);
                    DriveEdge edge = driveEdge(span, currentNode, nextNode);
                    double consumedSocPct = consumedSocPct(edge.distanceKm(), request.vehicle());
                    double arrivalSocPct = departure.socPct() - consumedSocPct;
                    if (arrivalSocPct + EPSILON < request.reserveSocPct()) {
                        continue;
                    }

                    SearchLabel nextLabel = transition(
                            span,
                            currentNode,
                            nextNode,
                            currentLabel,
                            departure,
                            arrivalSocPct,
                            edge
                    );
                    admit(nextIndex, nextLabel, bestByNode, queue);
                }
            }
        }

        return bestByNode.getOrDefault(nodes.size() - 1, List.of());
    }

    private static boolean dominates(SearchLabel a, SearchLabel b) {
        return a.socPct() + EPSILON >= b.socPct() && a.costSeconds() <= b.costSeconds() + EPSILON
                && b.visitedChargerIds().containsAll(a.visitedChargerIds());
    }
    private static void admit(int node, SearchLabel candidate, Map<Integer,List<SearchLabel>> labels, PriorityQueue<NodeLabel> queue) {
        var frontier = labels.computeIfAbsent(node, ignored -> new ArrayList<>());
        if (frontier.stream().anyMatch(existing -> dominates(existing, candidate))) return;
        frontier.removeIf(existing -> dominates(candidate, existing));
        if (frontier.size() >= MAX_LABELS_PER_NODE) throw new SearchLimitException();
        frontier.add(candidate);
        queue.add(new NodeLabel(node, candidate));
    }
    private static class SearchLimitException extends RuntimeException {}

    private SearchLabel transition(
            OptimizationRouteSpan span,
            RouteNode currentNode,
            RouteNode nextNode,
            SearchLabel currentLabel,
            DepartureOption departure,
            double arrivalSocPct,
            DriveEdge edge
    ) {
        List<PlannedChargeStop> chargeStops = currentLabel.chargeStops();
        Set<String> visitedChargerIds = currentLabel.visitedChargerIds();
        double chargingCostSeconds = departure.chargeMinutes() * 60.0;

        if (departure.chargeMinutes() > 0 && currentNode.candidate() != null) {
            chargeStops = new ArrayList<>(currentLabel.chargeStops());
            chargeStops.add(new PlannedChargeStop(
                    span.index(),
                    currentNode.sequence(),
                    span.to().itemId(),
                    currentNode.candidate(),
                    currentLabel.socPct(),
                    departure.socPct(),
                    departure.chargeMinutes(),
                    currentNode.candidate().deviationKm() * 2
            ));
            visitedChargerIds = new HashSet<>(currentLabel.visitedChargerIds());
            visitedChargerIds.add(currentNode.candidate().charger().id());
            chargingCostSeconds += CHARGING_STOP_OVERHEAD_SECONDS + riskPenaltySeconds(currentNode.candidate().charger());
        }

        return new SearchLabel(
                arrivalSocPct,
                currentLabel.costSeconds() + edge.durationSeconds() + chargingCostSeconds,
                currentLabel.drivingSeconds() + edge.durationSeconds(),
                currentLabel.chargingMinutes() + departure.chargeMinutes(),
                List.copyOf(chargeStops),
                Set.copyOf(visitedChargerIds)
        );
    }

    private List<DepartureOption> departureOptions(
            RouteNode node,
            SearchLabel label,
            EvRouteOptimizationRequest request
    ) {
        List<DepartureOption> options = new ArrayList<>();
        options.add(new DepartureOption(label.socPct(), 0));
        OptimizationChargerCandidate candidate = node.candidate();
        if (candidate == null
                || label.visitedChargerIds().contains(candidate.charger().id())
                || !compatible(candidate.charger(), request.vehicle())) {
            return options;
        }

        // An explicit per-stop target is an itinerary constraint, including a 100% target.
        Integer stopTarget = request.stops().stream()
                .filter(stop -> stop.itemId().equals(candidate.existingItemId()))
                .map(stop -> stop.targetBatteryPct())
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        if (stopTarget != null) {
            double departurePct = Math.max(label.socPct(), Math.min(100, Math.max(0, stopTarget)));
            Double minutes = chargeMinutes(label.socPct(), departurePct, candidate.charger(), request.vehicle());
            return minutes == null ? List.of() : List.of(new DepartureOption(departurePct, minutes));
        }

        Set<Integer> targetSocValues = new LinkedHashSet<>();
        targetSocValues.add((int) Math.round(request.targetSocPct()));
        int firstBucket = roundUp(label.socPct() + EPSILON, CHARGE_BUCKET_SIZE_PCT);
        for (int target = firstBucket; target <= MAX_CHARGE_SOC_PCT; target += CHARGE_BUCKET_SIZE_PCT) {
            targetSocValues.add(target);
        }
        for (int targetSoc : targetSocValues) {
            int boundedTargetSoc = Math.min(MAX_CHARGE_SOC_PCT, targetSoc);
            if (boundedTargetSoc <= label.socPct()) {
                continue;
            }
            Double chargeMinutes = chargeMinutes(
                    label.socPct(),
                    boundedTargetSoc,
                    candidate.charger(),
                    request.vehicle()
            );
            if (chargeMinutes != null && chargeMinutes > 0) {
                options.add(new DepartureOption(boundedTargetSoc, chargeMinutes));
            }
        }
        return options;
    }

    private List<RouteNode> routeNodes(OptimizationRouteSpan span) {
        List<RouteNode> nodes = new ArrayList<>();
        nodes.add(new RouteNode(0, 0, mandatoryCandidate(span.from())));
        int sequence = 1;
        for (OptimizationChargerCandidate candidate : span.candidates()) {
            nodes.add(new RouteNode(sequence, candidate.progressKm(), candidate));
            sequence++;
        }
        nodes.add(new RouteNode(sequence, span.distanceKm(), null));
        return nodes;
    }

    private OptimizationChargerCandidate mandatoryCandidate(EvRouteStopRequest stop) {
        if (!stop.chargerStop() || !stop.effectiveLocked()) {
            return null;
        }
        return new OptimizationChargerCandidate(stop.charger(), 0, 0, stop.itemId());
    }

    private DriveEdge driveEdge(OptimizationRouteSpan span, RouteNode from, RouteNode to) {
        double routeDistanceKm = Math.max(0, to.progressKm() - from.progressKm());
        double fromDeviationKm = from.candidate() == null ? 0 : from.candidate().deviationKm();
        double toDeviationKm = to.candidate() == null ? 0 : to.candidate().deviationKm();
        double distanceKm = routeDistanceKm + fromDeviationKm + toDeviationKm;
        double routeDurationSeconds = span.distanceKm() <= 0
                ? 0
                : span.durationSeconds() * routeDistanceKm / span.distanceKm();
        long detourDurationSeconds = Math.round((fromDeviationKm + toDeviationKm) / 60.0 * 3_600);
        return new DriveEdge(distanceKm, Math.round(routeDurationSeconds) + detourDurationSeconds);
    }

    private Double consumedSocPct(double distanceKm, EvVehicleSpec vehicle) {
        return CanonicalEnergy.calculate(new CanonicalEnergy.Input(vehicle.resolvedModel(), distanceKm, "ROUTED", 100.0, null, null, 12.0)).nominalSocUsePct();
    }

    private Double chargeMinutes(double arrivalSocPct, double targetSocPct, EvChargerResponse charger, EvVehicleSpec vehicle) {
        var connectors = charger.connectorTypes().stream().filter(vehicle.connectorTypes()::contains).toList();
        Double limit = connectors.stream().map(c -> DC_CONNECTORS.contains(c) ? vehicle.maxDcKw() : vehicle.maxAcKw())
                .filter(CanonicalEnergy::positive).max(Double::compare).orElse(null);
        Double power = limit != null && charger.maxKw() > 0 ? Math.min(charger.maxKw(), limit) : null;
        var model = vehicle.resolvedModel();
        return CanonicalEnergy.calculate(new CanonicalEnergy.Input(model, 0.0, "ROUTED", arrivalSocPct, arrivalSocPct,
                new CanonicalEnergy.Charging(targetSocPct, null, !connectors.isEmpty(), power), 12.0)).nominalChargeMinutes();
    }

    private boolean compatible(EvChargerResponse charger, EvVehicleSpec vehicle) {
        return charger.connectorTypes().stream().anyMatch(vehicle.connectorTypes()::contains);
    }

    private double riskPenaltySeconds(EvChargerResponse charger) {
        double penalty = Math.max(0, 1 - charger.confidenceScore()) * 12 * 60;
        if (charger.stale()) {
            penalty += 10 * 60;
        }
        if (charger.availableConnectors() == null) {
            penalty += 3 * 60;
        } else if (charger.availableConnectors() == 0) {
            penalty += 20 * 60;
        } else {
            double availabilityRatio = charger.availableConnectors() / (double) Math.max(1, charger.totalConnectors());
            penalty += (1 - availabilityRatio) * 5 * 60;
        }
        return penalty;
    }

    private int roundUp(double value, int bucketSize) {
        return (int) Math.ceil(value / bucketSize) * bucketSize;
    }

    private record RouteNode(int sequence, double progressKm, OptimizationChargerCandidate candidate) {
    }

    private record DriveEdge(double distanceKm, long durationSeconds) {
    }

    private record DepartureOption(double socPct, double chargeMinutes) {
    }

    private record NodeLabel(int nodeIndex, SearchLabel label) {
    }

    private record SearchLabel(
            double socPct,
            double costSeconds,
            long drivingSeconds,
            double chargingMinutes,
            List<PlannedChargeStop> chargeStops,
            Set<String> visitedChargerIds
    ) {
        SearchLabel withSoc(double soc) { return new SearchLabel(soc, costSeconds, drivingSeconds, chargingMinutes, chargeStops, visitedChargerIds); }
        static SearchLabel start(double startingSocPct) {
            return new SearchLabel(startingSocPct, 0, 0, 0, List.of(), Set.of());
        }
    }
}
