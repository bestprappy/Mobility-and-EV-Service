package com.navio.mobilityandevservice.domain.route;

import com.fasterxml.jackson.annotation.JsonValue;

public enum RouteSegmentStatus {
    ROUTED("routed"),
    FALLBACK("fallback");

    private final String value;

    RouteSegmentStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
