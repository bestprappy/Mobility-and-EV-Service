package com.navio.mobilityandevservice.service.simulation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.domain.optimization.EvVehicleSpec;

import java.io.IOException;
import java.util.Set;

/** Versioned battery-side baseline shared with the offline client projection. */
public final class EvSimulationModel {
    public record Policy(String version, double planningMarginFraction, double reserveSocPct,
                         double chargeEfficiency, double dcTaperStartPct, double dcTaperPowerFraction,
                         double stopOverheadMinutes) {}
    public static final Policy POLICY = load();
    private static final Set<EvConnectorType> DC = Set.of(EvConnectorType.CCS1, EvConnectorType.CCS2,
            EvConnectorType.CHADEMO, EvConnectorType.NACS, EvConnectorType.GB_T);

    private EvSimulationModel() {}

    private static Policy load() {
        try (var stream = EvSimulationModel.class.getResourceAsStream("/simulation/model-v1.json")) {
            if (stream == null) throw new IllegalStateException("Missing EV model contract");
            return new ObjectMapper().readValue(stream, Policy.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Invalid EV model contract", exception);
        }
    }

    public static double nominalEnergy(double distanceKm, double consumption) {
        if (!Double.isFinite(distanceKm) || distanceKm < 0 || !Double.isFinite(consumption) || consumption <= 0)
            throw new IllegalArgumentException("Invalid energy inputs");
        return distanceKm * consumption / 100;
    }

    public static double planningEnergy(double distanceKm, double consumption) {
        return nominalEnergy(distanceKm, consumption) * (1 + POLICY.planningMarginFraction());
    }

    public static int chargeMinutes(double arrival, double target, double batteryKwh, double powerKw, boolean dc) {
        if (!Double.isFinite(arrival) || !Double.isFinite(target) || !Double.isFinite(batteryKwh)
                || !Double.isFinite(powerKw) || batteryKwh <= 0) throw new IllegalArgumentException("Invalid charging inputs");
        if (powerKw <= 0 || target <= arrival) return 0;
        double start = Math.clamp(arrival, 0, 100);
        double end = Math.clamp(target, start, 100);
        double fast = dc ? Math.max(0, Math.min(end, POLICY.dcTaperStartPct()) - start) : end - start;
        double slow = dc ? Math.max(0, end - Math.max(start, POLICY.dcTaperStartPct())) : 0;
        double minutes = batteryKwh / 100 / (powerKw * POLICY.chargeEfficiency())
                * (fast + slow / POLICY.dcTaperPowerFraction()) * 60;
        return Math.max(0, (int) Math.ceil(minutes - 1e-9));
    }

    public static boolean usesDc(EvChargerResponse charger, EvVehicleSpec vehicle) {
        return charger.connectorTypes().stream().anyMatch(c -> vehicle.connectorTypes().contains(c) && DC.contains(c));
    }

    public static double chargingPower(EvChargerResponse charger, EvVehicleSpec vehicle) {
        if (charger.connectorTypes().stream().noneMatch(vehicle.connectorTypes()::contains)) return 0;
        return Math.min(charger.maxKw(), usesDc(charger, vehicle) ? vehicle.maxDcKw() : vehicle.maxAcKw());
    }

    public static int chargeMinutes(double arrival, double target, EvChargerResponse charger, EvVehicleSpec vehicle) {
        return chargeMinutes(arrival, target, vehicle.batteryKwh(), chargingPower(charger, vehicle), usesDc(charger, vehicle));
    }
}
