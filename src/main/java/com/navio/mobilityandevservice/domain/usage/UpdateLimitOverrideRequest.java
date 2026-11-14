package com.navio.mobilityandevservice.domain.usage;

import jakarta.validation.constraints.NotNull;

/** @param ignoreLimits true lets calls continue past this month's limits; false stops them at the limit again */
public record UpdateLimitOverrideRequest(@NotNull Boolean ignoreLimits) {
}
