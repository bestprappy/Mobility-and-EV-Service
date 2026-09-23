package com.navio.mobilityandevservice.domain.optimization;

import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.util.List;
import com.navio.mobilityandevservice.domain.energy.CanonicalEnergy;

public record EvVehicleSpec(
        @PositiveOrZero Double batteryKwh,
        @PositiveOrZero Double consumptionKwhPer100km,
        @PositiveOrZero Double maxAcKw,
        @PositiveOrZero Double maxDcKw,
        @NotEmpty List<@NotNull EvConnectorType> connectorTypes,
        @jakarta.validation.Valid CanonicalEnergy.Model energyModel
) {
    public EvVehicleSpec(Double batteryKwh, Double consumptionKwhPer100km, Double maxAcKw, Double maxDcKw, List<EvConnectorType> connectorTypes) {
        this(batteryKwh, consumptionKwhPer100km, maxAcKw, maxDcKw, connectorTypes, null);
    }
    public CanonicalEnergy.Model resolvedModel() {
        return energyModel != null ? energyModel : new CanonicalEnergy.Model("CONSUMPTION", consumptionKwhPer100km, null, null);
    }
    public EvVehicleSpec {
        connectorTypes = connectorTypes == null ? null : List.copyOf(connectorTypes);
    }
}
