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
    private static final int MAX_CHARGE_SOC_PCT = 95;
    private static final int CHARGE_BUCKET_SIZE_PCT = 5;
    private static final long CHARGING_STOP_OVERHEAD_SECONDS = 5 * 60L;
    private static final int MAX_ALTERNATIVE_PLANS = 8;
    private static final int MAX_SAME_STOP_PLANS = 5;
    static final String APPROXIMATE_DISTANCE_WARNING = "Distances are approximate until the selected stops are checked on the road route.";

    OptimizationSearchResult optimize(OptimizationRoute route, EvRouteOptimizationRequest request) {
        List<SearchLabel> labels = List.of(SearchLabel.start(request.startingSocPct()));
        List<String> warnings = new ArrayList<>();
        if (route.spans().stream().anyMatch(span -> !"ROUTED".equals(span.distanceQuality()) || !span.candidates().isEmpty())) warnings.add(APPROXIMATE_DISTANCE_WARNING);
        if ("RATED_RANGE".equals(request.vehicle().resolvedModel().modelKind())) warnings.add("Rated-range estimate: this is a preview only, so these charging stops can't be applied.");

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

        List<SearchLabel> ranked = labels.stream().sorted(Comparator.comparingDouble(SearchLabel::costSeconds)).toList();
        SearchLabel best = ranked.getFirst();
        List<OptimizationSearchResult> alternatives = alternativesTo(best, ranked, route, request).stream()
                .filter(label -> label.socPct() > best.socPct() + EPSILON)
                .map(label -> feasibleResult(route, label, warnings, List.of()))
                .toList();
        return feasibleResult(route, best, warnings, alternatives);
    }

    private static OptimizationSearchResult feasibleResult(OptimizationRoute route, SearchLabel label, List<String> warnings,
                                                           List<OptimizationSearchResult> alternatives) {
        Double observedAtEnd = route.spans().isEmpty() ? null : route.spans().getLast().to().observedSocPct();
        return new OptimizationSearchResult(true, observedAtEnd == null ? label.socPct() : observedAtEnd,
                label.drivingSeconds(), label.chargingMinutes(), label.chargeStops(), warnings, alternatives);
    }

    /**
     * Each alternative costs road requests to verify, so the list is bounded. Charging longer at the stops
     * the day already needs comes first, then the fullest finish for each number of stops, then an even
     * sample. Pruning can drop the same-stop variants in favour of another station that later fails the road
     * check, so they come from a second search over only the cheapest plan's stations.
     */
    private List<SearchLabel> alternativesTo(SearchLabel best, List<SearchLabel> ranked, OptimizationRoute route,
                                             EvRouteOptimizationRequest request) {
        Set<SearchLabel> chosen = new LinkedHashSet<>();
        List<SearchLabel> sameStops = chargingLongerAtTheSameStops(best, route, request);
        chosen.addAll(sampleKeepingLast(sameStops, MAX_SAME_STOP_PLANS));
        List<SearchLabel> rest = ranked.subList(1, ranked.size());
        Map<Integer, SearchLabel> fullestByStops = new HashMap<>();
        for (SearchLabel label : rest) {
            fullestByStops.merge(label.chargeStops().size(), label, (a, b) -> a.socPct() >= b.socPct() ? a : b);
        }
        chosen.addAll(fullestByStops.values());
        chosen.addAll(sampleKeepingLast(rest, MAX_ALTERNATIVE_PLANS));
        return chosen.stream().limit(MAX_ALTERNATIVE_PLANS)
                .sorted(Comparator.comparingDouble(SearchLabel::costSeconds)).toList();
    }

    private List<SearchLabel> chargingLongerAtTheSameStops(SearchLabel best, OptimizationRoute route, EvRouteOptimizationRequest request) {
        if (best.chargeStops().isEmpty()) return List.of();
        Set<String> stations = new HashSet<>();
        best.chargeStops().forEach(stop -> stations.add(stop.candidate().charger().id()));
        List<SearchLabel> labels = List.of(SearchLabel.start(request.startingSocPct()));
        try {
            for (OptimizationRouteSpan span : route.spans()) {
                Double observed = span.from().observedSocPct();
                if (observed != null) labels = labels.stream().map(label -> label.withSoc(observed)).toList();
                labels = optimizeSpan(span.withCandidates(span.candidates().stream()
                        .filter(candidate -> stations.contains(candidate.charger().id())).toList()), request, labels);
            }
        } catch (SearchLimitException limit) {
            return List.of();
        }
        return labels.stream()
                .filter(label -> sameStations(label, best) && label.socPct() > best.socPct() + EPSILON)
                .sorted(Comparator.comparingDouble(SearchLabel::socPct)).toList();
    }

    private static List<SearchLabel> sampleKeepingLast(List<SearchLabel> labels, int limit) {
        if (labels.size() <= limit) return labels;
        List<SearchLabel> sampled = new ArrayList<>(limit);
        for (int index = 1; index <= limit; index++) sampled.add(labels.get(index * labels.size() / limit - 1));
        return sampled;
    }

    private static boolean sameStations(SearchLabel a, SearchLabel b) {
        return a.chargeStops().stream().map(stop -> stop.candidate().charger().id()).toList()
                .equals(b.chargeStops().stream().map(stop -> stop.candidate().charger().id()).toList());
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
        // Only saved stops are tracked: one can project onto several spans but is one visit. A span's nodes
        // only move forward, so a new station cannot repeat within it, and tracking those would stop labels
        // pruning each other and exhaust the search on any corridor with many stations.
        Set<String> visitedChargerIds = currentLabel.visitedChargerIds();
        double chargingCostSeconds = departure.chargeMinutes() * 60.0;

        // A kept stop's saved charge happens anyway and the verifier replays it, so it needs no operation.
        if (departure.chargeMinutes() > 0 && currentNode.candidate() != null && !departure.savedPlan()) {
            chargeStops = new ArrayList<>(currentLabel.chargeStops());
            chargeStops.add(new PlannedChargeStop(
                    span.index(),
                    currentNode.sequence(),
                    currentNode.sequence() == 0 ? null : span.to().itemId(),
                    currentNode.candidate(),
                    currentLabel.socPct(),
                    departure.socPct(),
                    departure.chargeMinutes(),
                    currentNode.candidate().deviationKm() * 2
            ));
            if (currentNode.candidate().existingItemId() != null) {
                visitedChargerIds = new HashSet<>(currentLabel.visitedChargerIds());
                visitedChargerIds.add(currentNode.candidate().existingItemId());
            }
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
        DepartureOption noCharge = new DepartureOption(label.socPct(), 0, false);
        OptimizationChargerCandidate candidate = node.candidate();
        if (candidate == null
                || (candidate.existingItemId() != null && label.visitedChargerIds().contains(candidate.existingItemId()))
                || !compatible(candidate.charger(), request.vehicle())) {
            options.add(noCharge);
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
            return minutes == null ? List.of() : List.of(new DepartureOption(departurePct, minutes, false));
        }

        // Node 0 is a kept stop. Leaving it uncharged is not an option when its saved plan charges,
        // because the verifier replays that plan; pretending otherwise adds chargers it makes redundant.
        DepartureOption base = node.sequence() == 0 ? savedPlanOption(candidate, label, request, noCharge) : noCharge;
        options.add(base);

        Set<Integer> targetSocValues = new LinkedHashSet<>();
        int minimumTarget = (int) Math.ceil(request.targetSocPct());
        targetSocValues.add(minimumTarget);
        int firstBucket = roundUp(Math.max(base.socPct() + EPSILON, minimumTarget), CHARGE_BUCKET_SIZE_PCT);
        for (int target = firstBucket; target <= MAX_CHARGE_SOC_PCT; target += CHARGE_BUCKET_SIZE_PCT) {
            targetSocValues.add(target);
        }
        for (int targetSoc : targetSocValues) {
            int boundedTargetSoc = Math.min(MAX_CHARGE_SOC_PCT, targetSoc);
            if (boundedTargetSoc <= base.socPct()) {
                continue;
            }
            Double chargeMinutes = chargeMinutes(
                    label.socPct(),
                    boundedTargetSoc,
                    candidate.charger(),
                    request.vehicle()
            );
            if (chargeMinutes != null && chargeMinutes > 0) {
                options.add(new DepartureOption(boundedTargetSoc, chargeMinutes, false));
            }
        }
        return options;
    }

    private static DepartureOption savedPlanOption(OptimizationChargerCandidate candidate, SearchLabel label,
                                                   EvRouteOptimizationRequest request, DepartureOption noCharge) {
        EvRouteStopRequest saved = request.stops().stream()
                .filter(stop -> stop.itemId().equals(candidate.existingItemId()))
                .findFirst().orElse(null);
        if (saved == null || (saved.effectiveTargetSocPct() == null && saved.effectiveChargeMinutes() == null)) return noCharge;
        var energy = CanonicalEnergy.calculate(new CanonicalEnergy.Input(request.vehicle().resolvedModel(), 0.0, "ROUTED", label.socPct(), null,
                new CanonicalEnergy.Charging(saved.effectiveTargetSocPct(), saved.effectiveChargeMinutes(), true,
                        effectivePowerKw(candidate.charger(), request.vehicle())), request.reserveSocPct()));
        if (energy.departureSocPct() == null || energy.nominalChargeMinutes() == null || energy.nominalChargeMinutes() <= 0) return noCharge;
        return new DepartureOption(energy.departureSocPct(), energy.nominalChargeMinutes(), true);
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

    static Double chargeMinutes(double arrivalSocPct, double targetSocPct, EvChargerResponse charger, EvVehicleSpec vehicle) {
        if (targetSocPct <= arrivalSocPct) return 0.0;
        if (!compatible(charger, vehicle)) return null;
        Double power = effectivePowerKw(charger, vehicle);
        var model = vehicle.resolvedModel();
        return CanonicalEnergy.calculate(new CanonicalEnergy.Input(model, 0.0, "ROUTED", arrivalSocPct, arrivalSocPct,
                new CanonicalEnergy.Charging(targetSocPct, null, true, power), 12.0)).nominalChargeMinutes();
    }

    static Double effectivePowerKw(EvChargerResponse charger, EvVehicleSpec vehicle) {
        if (!compatible(charger, vehicle)) return null;
        var connectors = charger.connectorTypes().stream().filter(c -> c != EvConnectorType.OTHER)
                .filter(vehicle.connectorTypes()::contains).toList();
        Double limit = connectors.stream().map(c -> DC_CONNECTORS.contains(c) ? vehicle.maxDcKw() : vehicle.maxAcKw())
                .filter(CanonicalEnergy::positive).max(Double::compare).orElse(null);
        return limit != null && CanonicalEnergy.positive(charger.maxKw()) ? Math.min(charger.maxKw(), limit) : null;
    }

    static boolean compatible(EvChargerResponse charger, EvVehicleSpec vehicle) {
        return charger.status() == com.navio.mobilityandevservice.domain.ev.EvChargerStatus.ACTIVE
                && charger.connectorTypes().stream().filter(c -> c != EvConnectorType.OTHER)
                .anyMatch(vehicle.connectorTypes()::contains);
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

    private record DepartureOption(double socPct, double chargeMinutes, boolean savedPlan) {
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
