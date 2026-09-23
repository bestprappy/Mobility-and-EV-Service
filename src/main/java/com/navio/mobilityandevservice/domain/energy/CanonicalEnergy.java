package com.navio.mobilityandevservice.domain.energy;

import java.util.ArrayList;
import java.util.List;

/** navio-energy-v1: nominal calculations only; never infer usable capacity. */
public final class CanonicalEnergy {
    public static final String VERSION = "navio-energy-v1";
    public static final double EPSILON = 1e-9;
    private CanonicalEnergy() {}
    public record Model(@jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Pattern(regexp="CONSUMPTION|RATED_RANGE|UNAVAILABLE") String modelKind, @jakarta.validation.constraints.Positive Double consumptionKwhPer100km, @jakarta.validation.constraints.Positive Double usableBatteryCapacityKwh, @jakarta.validation.constraints.Positive Double ratedRangeKm) {}
    public record Charging(Double targetSocPct, Double durationMinutes, Boolean compatible, Double effectivePowerKw) {}
    public record Input(Model model, Double distanceKm, String distanceQuality, Double departureSocPct,
                        Double observedSocPct, Charging charging, Double reserveSocPct) {}
    public record Result(String policyVersion, String modelKind, boolean provisional, String uncertaintyStatus,
                         Double uncertaintyAllowance, String availability, List<String> reasons, String distanceQuality,
                         Double nominalEnergyKwh, Double nominalSocUsePct, Double rawPredictedArrivalSocPct,
                         Double displayArrivalSocPct, Double observedSocPct, Double departureSocPct,
                         String predictedArrivalReserveStatus, String predictedLegFeasibility,
                         Double chargeEnergyKwh, Double nominalChargeMinutes) {}
    public record Stop(String id, String dayId, Double distanceKm, String distanceQuality, Double observedSocPct, Charging charging) {}
    public record ProjectedStop(String id, String dayId, Double startingSocPct, Result result) {}
    public static List<ProjectedStop> project(Model model, Double initialSocPct, List<Stop> stops, double reserveSocPct) {
        if (initialSocPct != null && !validSoc(initialSocPct)) throw new IllegalArgumentException("Starting SoC must be between 0 and 100");
        var results = new ArrayList<ProjectedStop>();
        Double forward = initialSocPct;
        for (var stop : stops) {
            var result = calculate(new Input(model, stop.distanceKm(), stop.distanceQuality(), forward, stop.observedSocPct(), stop.charging(), reserveSocPct));
            results.add(new ProjectedStop(stop.id(), stop.dayId(), forward, result));
            forward = result.departureSocPct();
        }
        return List.copyOf(results);
    }
    public static boolean positive(Double value) { return value != null && Double.isFinite(value) && value > 0; }
    public static boolean validSoc(Double value) { return value != null && Double.isFinite(value) && value >= 0 && value <= 100; }
    public static Result calculate(Input input) {
        var model = input.model();
        var reasons = new ArrayList<String>();
        Double capacity = positive(model.usableBatteryCapacityKwh()) ? model.usableBatteryCapacityKwh() : null;
        Double distance = !"UNAVAILABLE".equals(input.distanceQuality()) && input.distanceKm() != null
                && Double.isFinite(input.distanceKm()) && input.distanceKm() >= 0 ? input.distanceKm() : null;
        Double energy = null, depletion = null;
        if (distance == null) reasons.add("DISTANCE_UNAVAILABLE");
        else if ("CONSUMPTION".equals(model.modelKind()) && positive(model.consumptionKwhPer100km())) {
            energy = distance * model.consumptionKwhPer100km() / 100;
            if (capacity != null) depletion = energy / capacity * 100;
            else reasons.add("USABLE_CAPACITY_UNAVAILABLE");
        } else if ("RATED_RANGE".equals(model.modelKind()) && positive(model.ratedRangeKm())) {
            depletion = distance / model.ratedRangeKm() * 100;
            if (capacity != null) energy = capacity * distance / model.ratedRangeKm();
            else reasons.add("USABLE_CAPACITY_UNAVAILABLE");
        } else reasons.add("ENERGY_MODEL_UNAVAILABLE");
        if (distance != null && distance == 0) { energy = 0.0; depletion = 0.0; }
        Double arrival = validSoc(input.departureSocPct()) && depletion != null ? input.departureSocPct() - depletion : null;
        if (!validSoc(input.departureSocPct())) reasons.add("UPSTREAM_SOC_UNAVAILABLE");
        Double observed = input.observedSocPct();
        if (observed != null && !validSoc(observed)) throw new IllegalArgumentException("Observed SoC must be between 0 and 100");
        boolean infeasible = arrival != null && arrival < -EPSILON;
        if (infeasible) reasons.add("PREDICTED_LEG_INFEASIBLE");
        Double departure = observed;
        if (departure == null && arrival != null && !infeasible) departure = Math.max(0, arrival);
        Double chargeEnergy = 0.0, chargeMinutes = 0.0;
        var charge = input.charging();
        if (charge != null) {
            if (departure == null) {
                chargeEnergy = null; chargeMinutes = null; reasons.add("CHARGING_START_UNAVAILABLE");
            } else if (!Boolean.TRUE.equals(charge.compatible())) {
                if (charge.compatible() == null) {
                    departure = null; chargeEnergy = null; chargeMinutes = null; reasons.add("CONNECTOR_COMPATIBILITY_UNAVAILABLE");
                } else reasons.add("INCOMPATIBLE_CHARGER");
            } else {
                Double power = positive(charge.effectivePowerKw()) ? charge.effectivePowerKw() : null;
                if (charge.targetSocPct() != null) {
                    if (!validSoc(charge.targetSocPct())) throw new IllegalArgumentException("Charge target must be between 0 and 100");
                    double delta = Math.max(0, charge.targetSocPct() - departure);
                    chargeEnergy = delta == 0 ? Double.valueOf(0) : capacity == null ? null : Double.valueOf(capacity * delta / 100);
                    departure += delta;
                    chargeMinutes = chargeEnergy != null && chargeEnergy == 0 ? Double.valueOf(0)
                            : chargeEnergy == null || power == null ? null : Double.valueOf(60 * chargeEnergy / power);
                } else if (charge.durationMinutes() != null && Double.isFinite(charge.durationMinutes()) && charge.durationMinutes() >= 0 && power != null && capacity != null) {
                    chargeEnergy = Math.min((100 - departure) * capacity / 100, charge.durationMinutes() * power / 60);
                    chargeMinutes = 60 * chargeEnergy / power;
                    departure += chargeEnergy / capacity * 100;
                } else { chargeEnergy = null; chargeMinutes = null; departure = null; }
                if (chargeEnergy == null) reasons.add("CHARGING_ENERGY_UNAVAILABLE");
                if (chargeMinutes == null) reasons.add("CHARGING_DURATION_UNAVAILABLE");
            }
        }
        double reserve = input.reserveSocPct() == null ? 12 : input.reserveSocPct();
        if (!validSoc(reserve)) throw new IllegalArgumentException("Reserve must be between 0 and 100");
        return new Result(VERSION, model.modelKind(), "RATED_RANGE".equals(model.modelKind()), "UNCALIBRATED", null,
                energy == null && depletion == null ? "UNAVAILABLE" : reasons.stream().anyMatch(r -> r.endsWith("UNAVAILABLE")) ? "PARTIAL" : "AVAILABLE",
                List.copyOf(reasons), distance == null ? "UNAVAILABLE" : input.distanceQuality(), energy, depletion, arrival,
                arrival == null ? null : Math.max(0, Math.min(100, arrival)), observed, departure,
                arrival == null ? "UNKNOWN" : arrival + EPSILON >= reserve ? "AT_OR_ABOVE_RESERVE" : "BELOW_RESERVE",
                arrival == null ? "UNKNOWN" : infeasible ? "INFEASIBLE" : "FEASIBLE", chargeEnergy, chargeMinutes);
    }
}
