package com.navio.mobilityandevservice.domain.route;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/** How the traveller covers one leg: by car, by public transit, or on foot. */
public enum LegTravelMode {
    DRIVE("drive"),
    TRANSIT("transit"),
    WALK("walk");

    private final String value;

    LegTravelMode(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static LegTravelMode fromValue(String value) {
        return Arrays.stream(values())
                .filter(mode -> mode.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported travel mode: " + value));
    }
}
