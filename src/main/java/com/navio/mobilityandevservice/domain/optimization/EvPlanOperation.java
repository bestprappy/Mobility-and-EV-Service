package com.navio.mobilityandevservice.domain.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;

public record EvPlanOperation(
        EvPlanOperationType type,
        String oldItemId,
        String beforeItemId,
        int sequence,
        EvChargerResponse charger,
        int estimatedChargeMinutes,
        int arrivalSocPct,
        int departureSocPct,
        double detourKm,
        String reason
) {
}
