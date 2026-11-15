package com.navio.mobilityandevservice.domain.optimization;

import java.util.List;

public record EvRouteOptimizationResponse(
        String blockId,
        boolean feasible,
        List<EvPlanOperation> operations,
        Double finalSocPct,
        long totalDrivingSeconds,
        double totalChargingMinutes,
        String message,
        List<String> warnings,
        String energyPolicyVersion
) {
    public EvRouteOptimizationResponse(String blockId, boolean feasible, List<EvPlanOperation> operations,
                                      Double finalSocPct, long totalDrivingSeconds, double totalChargingMinutes,
                                      String message, List<String> warnings) {
        this(blockId, feasible, operations, finalSocPct, totalDrivingSeconds, totalChargingMinutes, message, warnings,
                com.navio.mobilityandevservice.domain.energy.CanonicalEnergy.VERSION);
    }
    public EvRouteOptimizationResponse {
        operations = operations == null ? List.of() : List.copyOf(operations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
