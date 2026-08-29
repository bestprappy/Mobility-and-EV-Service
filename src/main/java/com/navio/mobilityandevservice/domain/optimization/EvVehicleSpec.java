package com.navio.mobilityandevservice.domain.optimization;

import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;

public record EvVehicleSpec(
        @NotNull @Positive Double batteryKwh,
        @NotNull @Positive Double consumptionKwhPer100km,
        @NotNull @PositiveOrZero Double maxAcKw,
        @NotNull @PositiveOrZero Double maxDcKw,
        @NotEmpty List<@NotNull EvConnectorType> connectorTypes
) {
    public EvVehicleSpec {
        connectorTypes = connectorTypes == null ? null : List.copyOf(connectorTypes);
    }
}
