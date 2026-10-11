package com.navio.mobilityandevservice.domain.route;

/**
 * A public transit line as the provider names and colours it.
 *
 * @param color       the operator's line colour as {@code #rrggbb}, or {@code null} when unpublished
 * @param vehicleType provider vehicle type such as {@code SUBWAY}, {@code HEAVY_RAIL} or {@code BUS}
 */
public record TransitLineResponse(
        String name,
        String shortName,
        String color,
        String textColor,
        String vehicleType
) {
}
