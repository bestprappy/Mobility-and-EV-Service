package com.navio.mobilityandevservice.domain.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;

public record EvPlanOperation(
        EvPlanOperationType type,
        String oldItemId,
        String beforeItemId,
        int sequence,
        EvChargerResponse charger,
        double estimatedChargeMinutes,
        double arrivalSocPct,
        double departureSocPct,
        double detourKm,
        String reason
) {
}
