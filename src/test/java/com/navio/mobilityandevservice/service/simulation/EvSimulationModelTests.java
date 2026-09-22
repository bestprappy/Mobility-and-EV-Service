package com.navio.mobilityandevservice.service.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class EvSimulationModelTests {
    @Test
    void matchesIndependentArithmeticCasesSharedWithClient() throws Exception {
        try (var input = getClass().getResourceAsStream("/simulation/reference-v1.json")) {
            var data = new ObjectMapper().readTree(input);
            for (var test : data.get("energy")) {
                assertThat(EvSimulationModel.nominalEnergy(test.get("distanceKm").asDouble(), test.get("consumption").asDouble()))
                        .isCloseTo(test.get("nominalKwh").asDouble(), within(1e-9));
                assertThat(EvSimulationModel.planningEnergy(test.get("distanceKm").asDouble(), test.get("consumption").asDouble()))
                        .isCloseTo(test.get("planningKwh").asDouble(), within(1e-9));
            }
            for (var test : data.get("charging")) {
                assertThat(EvSimulationModel.chargeMinutes(test.get("arrival").asDouble(), test.get("target").asDouble(),
                        test.get("battery").asDouble(), test.get("power").asDouble(), test.get("dc").asBoolean()))
                        .isEqualTo(test.get("minutes").asInt());
            }
        }
    }

    @Test
    void refusesNonfiniteAndInvalidInputs() {
        assertThatThrownBy(() -> EvSimulationModel.nominalEnergy(Double.NaN, 16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvSimulationModel.nominalEnergy(-1, 16)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EvSimulationModel.chargeMinutes(10, 80, 0, 100, true)).isInstanceOf(IllegalArgumentException.class);
    }
}
