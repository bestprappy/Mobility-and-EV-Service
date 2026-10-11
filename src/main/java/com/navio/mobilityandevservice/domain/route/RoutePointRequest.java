package com.navio.mobilityandevservice.domain.route;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One stop of a day's route.
 *
 * @param arrivalMode how the leg ending at this stop is travelled; {@code null} means the
 *                    request's driving profile. Ignored on the first stop of a group.
 */
public record RoutePointRequest(
        @NotBlank @Size(max = 128) String id,
        @NotBlank @Size(max = 200) String name,
        @NotNull RoutePointType type,
        @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
        @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
        LegTravelMode arrivalMode
) {

    public RoutePointRequest(String id, String name, RoutePointType type, double lat, double lng) {
        this(id, name, type, lat, lng, null);
    }
}
