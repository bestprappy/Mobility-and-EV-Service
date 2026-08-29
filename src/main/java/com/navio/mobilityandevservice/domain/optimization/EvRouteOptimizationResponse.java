package com.navio.mobilityandevservice.domain.optimization;

import java.util.List;

public record EvRouteOptimizationResponse(
        String blockId,
        boolean feasible,
        List<EvPlanOperation> operations,
        int finalSocPct,
        long totalDrivingSeconds,
        int totalChargingMinutes,
        String message,
        List<String> warnings
) {
    public EvRouteOptimizationResponse {
        operations = operations == null ? List.of() : List.copyOf(operations);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
