package com.navio.mobilityandevservice.service.optimization;

import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AutoAddArrivalPolicyTests {

    private static EvRouteOptimizationRequest request(double threshold, Double minimumArrival, boolean insertionOnly) {
        return new EvRouteOptimizationRequest("day-1", List.of(), null, 80.0, threshold, 100.0, 20.0,
                List.of(), List.of(), insertionOnly, minimumArrival);
    }

    @Test
    void thresholdOnlyAutoAddLetsAChargerBeReachedDownToTheFixedFloor() {
        assertThat(AutoAddArrivalPolicy.requiredArrivalSocPct(request(87, null, true), true, false))
                .isEqualTo(AutoAddArrivalPolicy.CHARGER_FLOOR_SOC_PCT);
    }

    @Test
    void thresholdOnlyAutoAddNeverRaisesTheChargerFloorAboveALowThreshold() {
        assertThat(AutoAddArrivalPolicy.requiredArrivalSocPct(request(6, null, true), true, false)).isEqualTo(6.0);
    }

    @Test
    void thresholdOnlyAutoAddHoldsItineraryStopsAndTheFinalDestinationToTheThreshold() {
        var request = request(30, null, true);
        assertThat(AutoAddArrivalPolicy.requiredArrivalSocPct(request, false, false)).isEqualTo(30.0);
        assertThat(AutoAddArrivalPolicy.requiredArrivalSocPct(request, false, true)).isEqualTo(30.0);
    }

    @Test
    void aClientThatSendsMinimumArrivalKeepsItsFinalDestinationAndChargerFloor() {
        var request = request(30, 15.0, true);
        assertThat(AutoAddArrivalPolicy.requiredArrivalSocPct(request, true, false)).isEqualTo(15.0);
        assertThat(AutoAddArrivalPolicy.requiredArrivalSocPct(request, false, true)).isEqualTo(15.0);
    }

    @Test
    void nonInsertionOptimizationKeepsTheReserveAsAFloorAtChargers() {
        assertThat(AutoAddArrivalPolicy.requiredArrivalSocPct(request(30, null, false), true, false)).isEqualTo(30.0);
    }
}
