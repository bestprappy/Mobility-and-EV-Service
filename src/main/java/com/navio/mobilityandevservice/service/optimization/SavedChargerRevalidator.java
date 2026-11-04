package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvChargerStatus;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.optimization.EvRouteStopRequest;
import com.navio.mobilityandevservice.service.EvChargerService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Saved stops hold what the provider said when they were added, so a station can close long before the
 * trip. Every charging stop a plan relies on is checked again, up to a bound, across all the trip's days.
 * A station that cannot be checked keeps its saved evidence and is reported, never shown as fresh.
 */
@Component
@Slf4j
public class SavedChargerRevalidator {

    static final int MAX_STATIONS_CHECKED = 24;

    private final EvChargerService chargers;

    public SavedChargerRevalidator(EvChargerService chargers) {
        this.chargers = chargers;
    }

    /** For callers with no provider to ask: every saved stop stays as saved and nothing is claimed about it. */
    static SavedChargerRevalidator none() {
        return new SavedChargerRevalidator(null);
    }

    record ClosedStop(String name, int dayNumber, boolean selectedDay, EvRouteStopRequest stop, EvChargerStatus status) {
        String statusText() {
            return status == EvChargerStatus.PERMANENTLY_CLOSED ? "permanently closed" : "temporarily closed";
        }
    }

    record Outcome(EvRouteOptimizationRequest request, List<ClosedStop> closed, int uncheckedStations) {
    }

    Outcome check(EvRouteOptimizationRequest request) {
        if (chargers == null) return new Outcome(request, List.of(), 0);
        Set<String> ids = new LinkedHashSet<>();
        // The selected day comes first: if the bound is reached, its stops are the ones to have checked.
        collectIds(request.stops(), ids);
        request.precedingDays().forEach(day -> collectIds(day.stops(), ids));
        request.followingDays().forEach(day -> collectIds(day.stops(), ids));
        List<String> toCheck = ids.stream().limit(MAX_STATIONS_CHECKED).toList();
        Map<String, EvChargerResponse> live = lookUp(toCheck);

        List<ClosedStop> closed = new ArrayList<>();
        int selectedDayNumber = request.precedingDays().size() + 1;
        List<EvRouteOptimizationRequest.Day> preceding = new ArrayList<>();
        for (int index = 0; index < request.precedingDays().size(); index++) {
            var day = request.precedingDays().get(index);
            preceding.add(new EvRouteOptimizationRequest.Day(day.blockId(), refresh(day.stops(), live, index + 1, false, closed)));
        }
        List<EvRouteStopRequest> stops = refresh(request.stops(), live, selectedDayNumber, true, closed);
        List<EvRouteOptimizationRequest.Day> following = new ArrayList<>();
        for (int index = 0; index < request.followingDays().size(); index++) {
            var day = request.followingDays().get(index);
            following.add(new EvRouteOptimizationRequest.Day(day.blockId(), refresh(day.stops(), live, selectedDayNumber + index + 1, false, closed)));
        }
        return new Outcome(new EvRouteOptimizationRequest(request.blockId(), stops, request.vehicle(), request.startingSocPct(),
                request.reserveSocPct(), request.targetSocPct(), request.maximumDetourKm(), preceding, following),
                closed, ids.size() - live.size());
    }

    private static void collectIds(List<EvRouteStopRequest> stops, Set<String> ids) {
        stops.stream().filter(EvRouteStopRequest::chargerStop).map(stop -> stop.charger().id()).forEach(ids::add);
    }

    private Map<String, EvChargerResponse> lookUp(List<String> ids) {
        Map<String, EvChargerResponse> live = new HashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Map<String, Future<EvChargerResponse>> lookups = new HashMap<>();
            for (String id : ids) lookups.put(id, executor.submit(() -> chargers.byId(id)));
            for (var lookup : lookups.entrySet()) {
                try {
                    live.put(lookup.getKey(), lookup.getValue().get());
                } catch (ExecutionException failed) {
                    log.warn("Could not re-check a saved EV station: {}", failed.getCause().toString());
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Re-checking saved EV stations was interrupted");
        }
        return live;
    }

    private static List<EvRouteStopRequest> refresh(List<EvRouteStopRequest> stops, Map<String, EvChargerResponse> live,
                                                    int dayNumber, boolean selectedDay, List<ClosedStop> closed) {
        List<EvRouteStopRequest> refreshed = new ArrayList<>(stops.size());
        for (EvRouteStopRequest stop : stops) {
            EvChargerResponse current = stop.chargerStop() ? live.get(stop.charger().id()) : null;
            if (current == null) {
                refreshed.add(stop);
                continue;
            }
            EvRouteStopRequest updated = new EvRouteStopRequest(stop.itemId(), stop.name(), stop.lat(), stop.lng(), current, stop.locked(),
                    stop.selectionSource(), stop.targetBatteryPct(), stop.observedSocPct(), stop.estimatedChargeMinutes(),
                    stop.chargingDurationMinutes(), stop.plannedDepartureSocPct());
            refreshed.add(updated);
            if (current.status() == EvChargerStatus.TEMPORARILY_CLOSED || current.status() == EvChargerStatus.PERMANENTLY_CLOSED) {
                closed.add(new ClosedStop(stop.name(), dayNumber, selectedDay, updated, current.status()));
            }
        }
        return refreshed;
    }
}
