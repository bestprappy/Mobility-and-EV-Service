package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.optimization.EvRouteStopRequest;
import com.navio.mobilityandevservice.domain.optimization.EvVehicleSpec;
import org.springframework.stereotype.Component;

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
    private static final double ENERGY_SAFETY_FACTOR = 1.12;
    private static final int MAX_CHARGE_SOC_PCT = 92;
    private static final int CHARGE_BUCKET_SIZE_PCT = 5;
    private static final long CHARGING_STOP_OVERHEAD_SECONDS = 5 * 60L;

    OptimizationSearchResult optimize(OptimizationRoute route, EvRouteOptimizationRequest request) {
        List<SearchLabel> labels = List.of(SearchLabel.start((int) Math.floor(request.startingSocPct())));
        List<String> warnings = new ArrayList<>();

        for (OptimizationRouteSpan span : route.spans()) {
            labels = optimizeSpan(span, request, labels);
            if (labels.isEmpty()) {
                warnings.add("No safe compatible charging plan was found between "
                        + span.from().name() + " and " + span.to().name() + ".");
                return new OptimizationSearchResult(false, 0, 0, 0, List.of(), warnings);
            }
        }

        SearchLabel best = labels.stream().min(Comparator.comparingDouble(SearchLabel::costSeconds)).orElseThrow();
        return new OptimizationSearchResult(
                true,
                best.socPct(),
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
        Map<NodeSocKey, SearchLabel> bestByNodeAndSoc = new HashMap<>();

        for (SearchLabel incoming : incomingLabels) {
            NodeSocKey key = new NodeSocKey(0, incoming.socPct());
            SearchLabel current = bestByNodeAndSoc.get(key);
            if (current == null || incoming.costSeconds() < current.costSeconds()) {
                bestByNodeAndSoc.put(key, incoming);
                queue.add(new NodeLabel(0, incoming));
            }
        }

        while (!queue.isEmpty()) {
            NodeLabel currentNodeLabel = queue.poll();
            int nodeIndex = currentNodeLabel.nodeIndex();
            SearchLabel currentLabel = currentNodeLabel.label();
            NodeSocKey currentKey = new NodeSocKey(nodeIndex, currentLabel.socPct());
            if (bestByNodeAndSoc.get(currentKey) != currentLabel) {
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
                    int consumedSocPct = consumedSocPct(edge.distanceKm(), request.vehicle());
                    int arrivalSocPct = departure.socPct() - consumedSocPct;
                    if (arrivalSocPct < Math.ceil(request.reserveSocPct())) {
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
                    NodeSocKey nextKey = new NodeSocKey(nextIndex, arrivalSocPct);
                    SearchLabel currentBest = bestByNodeAndSoc.get(nextKey);
                    if (currentBest == null || nextLabel.costSeconds() < currentBest.costSeconds()) {
                        bestByNodeAndSoc.put(nextKey, nextLabel);
                        queue.add(new NodeLabel(nextIndex, nextLabel));
                    }
                }
            }
        }

        int endIndex = nodes.size() - 1;
        List<SearchLabel> endLabels = bestByNodeAndSoc.entrySet().stream()
                .filter(entry -> entry.getKey().nodeIndex() == endIndex)
                .map(Map.Entry::getValue)
                .sorted(Comparator.comparingInt(SearchLabel::socPct).reversed())
                .toList();
        return removeDominated(endLabels);
    }

    private List<SearchLabel> removeDominated(List<SearchLabel> labels) {
        List<SearchLabel> nonDominated = new ArrayList<>();
        double bestCostAtHigherSoc = Double.MAX_VALUE;
        for (SearchLabel label : labels) {
            if (label.costSeconds() < bestCostAtHigherSoc) {
                nonDominated.add(label);
                bestCostAtHigherSoc = label.costSeconds();
            }
        }
        return nonDominated;
    }

    private SearchLabel transition(
            OptimizationRouteSpan span,
            RouteNode currentNode,
            RouteNode nextNode,
            SearchLabel currentLabel,
            DepartureOption departure,
            int arrivalSocPct,
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
            int departurePct = Math.max(label.socPct(), Math.min(100, Math.max(0, stopTarget)));
            return List.of(new DepartureOption(departurePct,
                    chargeMinutes(label.socPct(), departurePct, candidate.charger(), request.vehicle())));
        }

        Set<Integer> targetSocValues = new LinkedHashSet<>();
        targetSocValues.add((int) Math.round(request.targetSocPct()));
        int firstBucket = roundUp(label.socPct() + 1, CHARGE_BUCKET_SIZE_PCT);
        for (int target = firstBucket; target <= MAX_CHARGE_SOC_PCT; target += CHARGE_BUCKET_SIZE_PCT) {
            targetSocValues.add(target);
        }
        for (int targetSoc : targetSocValues) {
            int boundedTargetSoc = Math.min(MAX_CHARGE_SOC_PCT, targetSoc);
            if (boundedTargetSoc <= label.socPct()) {
                continue;
            }
            int chargeMinutes = chargeMinutes(
                    label.socPct(),
                    boundedTargetSoc,
                    candidate.charger(),
                    request.vehicle()
            );
            if (chargeMinutes > 0) {
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

    private int consumedSocPct(double distanceKm, EvVehicleSpec vehicle) {
        double energyKwh = distanceKm * vehicle.consumptionKwhPer100km() / 100.0 * ENERGY_SAFETY_FACTOR;
        return Math.max(1, (int) Math.ceil(energyKwh / vehicle.batteryKwh() * 100));
    }

    private int chargeMinutes(
            int arrivalSocPct,
            int targetSocPct,
            EvChargerResponse charger,
            EvVehicleSpec vehicle
    ) {
        // Rate the stop on the connectors the car can actually plug into. A station that offers both
        // CCS2 and Type 2 must be treated as AC for a Type 2 only car, otherwise the plan charges it
        // at DC speed and badly under-estimates the stop.
        List<EvConnectorType> usableConnectors = charger.connectorTypes().stream()
                .filter(vehicle.connectorTypes()::contains)
                .toList();
        if (usableConnectors.isEmpty()) {
            return 0;
        }
        boolean dc = usableConnectors.stream().anyMatch(DC_CONNECTORS::contains);
        double vehicleLimitKw = dc ? vehicle.maxDcKw() : vehicle.maxAcKw();
        double effectiveKw = Math.min(charger.maxKw(), vehicleLimitKw);
        if (effectiveKw <= 0) {
            return 0;
        }

        int lowerTarget = Math.min(targetSocPct, 80);
        double fastEnergyKwh = Math.max(0, lowerTarget - arrivalSocPct) / 100.0 * vehicle.batteryKwh();
        double taperedEnergyKwh = Math.max(0, targetSocPct - Math.max(arrivalSocPct, 80))
                / 100.0 * vehicle.batteryKwh();
        double hours = fastEnergyKwh / (effectiveKw * 0.90)
                + taperedEnergyKwh / (effectiveKw * 0.45);
        return Math.max(1, (int) Math.ceil(hours * 60));
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

    private int roundUp(int value, int bucketSize) {
        return ((value + bucketSize - 1) / bucketSize) * bucketSize;
    }

    private record RouteNode(int sequence, double progressKm, OptimizationChargerCandidate candidate) {
    }

    private record DriveEdge(double distanceKm, long durationSeconds) {
    }

    private record DepartureOption(int socPct, int chargeMinutes) {
    }

    private record NodeSocKey(int nodeIndex, int socPct) {
    }

    private record NodeLabel(int nodeIndex, SearchLabel label) {
    }

    private record SearchLabel(
            int socPct,
            double costSeconds,
            long drivingSeconds,
            int chargingMinutes,
            List<PlannedChargeStop> chargeStops,
            Set<String> visitedChargerIds
    ) {
        static SearchLabel start(int startingSocPct) {
            return new SearchLabel(startingSocPct, 0, 0, 0, List.of(), Set.of());
        }
    }
}
