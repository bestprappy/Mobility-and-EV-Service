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
        EvChargerSelectionSource selectionSource,
        @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(100) Integer targetBatteryPct,
        @DecimalMin("0.0") @DecimalMax("100.0") Double observedSocPct,
        @jakarta.validation.constraints.PositiveOrZero Integer estimatedChargeMinutes,
        @DecimalMin("0.0") @DecimalMax("2147483647.0") Double chargingDurationMinutes,
        @DecimalMin("0.0") @DecimalMax("100.0") Double plannedDepartureSocPct
) {
    public Double effectiveTargetSocPct() {
        if (targetBatteryPct != null) return targetBatteryPct.doubleValue();
        return plannedDepartureSocPct;
    }
    public Double effectiveChargeMinutes() {
        return chargingDurationMinutes != null ? chargingDurationMinutes : estimatedChargeMinutes == null ? null : estimatedChargeMinutes.doubleValue();
    }
    public EvRouteStopRequest(String itemId, String name, double lat, double lng, EvChargerResponse charger, Boolean locked, EvChargerSelectionSource selectionSource, Integer targetBatteryPct, Double observedSocPct, Integer estimatedChargeMinutes) {
        this(itemId, name, lat, lng, charger, locked, selectionSource, targetBatteryPct, observedSocPct, estimatedChargeMinutes, null, null);
    }
    public EvRouteStopRequest(String itemId, String name, double lat, double lng, EvChargerResponse charger, Boolean locked, EvChargerSelectionSource selectionSource, Integer targetBatteryPct, Double observedSocPct) {
        this(itemId, name, lat, lng, charger, locked, selectionSource, targetBatteryPct, observedSocPct, null);
    }
    public EvRouteStopRequest(String itemId, String name, double lat, double lng, EvChargerResponse charger, Boolean locked, EvChargerSelectionSource selectionSource, Integer targetBatteryPct) {
        this(itemId, name, lat, lng, charger, locked, selectionSource, targetBatteryPct, null);
    }
    public EvRouteStopRequest(String itemId, String name, double lat, double lng, EvChargerResponse charger, Boolean locked, EvChargerSelectionSource selectionSource) {
        this(itemId, name, lat, lng, charger, locked, selectionSource, null);
    }
    public boolean chargerStop() {
        return charger != null;
    }

    public boolean effectiveLocked() {
        return chargerStop() && (Boolean.TRUE.equals(locked) || targetBatteryPct != null || observedSocPct != null);
    }

    public EvChargerSelectionSource effectiveSelectionSource() {
        return selectionSource == null ? EvChargerSelectionSource.MANUAL : selectionSource;
    }
}
