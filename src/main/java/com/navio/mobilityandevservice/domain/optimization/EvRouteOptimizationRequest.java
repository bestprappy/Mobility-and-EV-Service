package com.navio.mobilityandevservice.domain.optimization;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record EvRouteOptimizationRequest(
        @NotBlank @Size(max = 160) String blockId,
        @NotNull @Size(min = 2, max = 25) List<@Valid EvRouteStopRequest> stops,
        @NotNull @Valid EvVehicleSpec vehicle,
        @NotNull @DecimalMin("1.0") @DecimalMax("100.0") Double startingSocPct,
        @NotNull @DecimalMin("1.0") @DecimalMax("40.0") Double reserveSocPct,
        @NotNull @DecimalMin("20.0") @DecimalMax("95.0") Double targetSocPct,
        @NotNull @DecimalMin("1.0") @DecimalMax("50.0") Double maximumDetourKm
) {
    public EvRouteOptimizationRequest {
        stops = stops == null ? null : List.copyOf(stops);
    }
}
