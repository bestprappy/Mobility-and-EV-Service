package com.navio.mobilityandevservice.domain.route;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

public enum RouteProfile {
    DRIVING_TRAFFIC("driving-traffic"),
    DRIVING("driving"),
    WALKING("walking"),
    CYCLING("cycling");

    private final String value;

    RouteProfile(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static RouteProfile fromValue(String value) {
        return Arrays.stream(values())
                .filter(profile -> profile.value.equalsIgnoreCase(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported route profile: " + value));
    }
}
