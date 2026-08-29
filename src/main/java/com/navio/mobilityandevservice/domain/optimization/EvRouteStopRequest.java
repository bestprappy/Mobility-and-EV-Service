package com.navio.mobilityandevservice.domain.optimization;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record EvRouteStopRequest(
        @NotBlank @Size(max = 160) String itemId,
        @NotBlank @Size(max = 240) String name,
        @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
        @Valid EvChargerResponse charger,
        Boolean locked,
        EvChargerSelectionSource selectionSource
) {
    public boolean chargerStop() {
        return charger != null;
    }

    public boolean effectiveLocked() {
        return chargerStop() && Boolean.TRUE.equals(locked);
    }

    public EvChargerSelectionSource effectiveSelectionSource() {
        return selectionSource == null ? EvChargerSelectionSource.MANUAL : selectionSource;
    }
}
