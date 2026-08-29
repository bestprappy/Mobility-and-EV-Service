package com.navio.mobilityandevservice.domain.ev;

import com.fasterxml.jackson.annotation.JsonValue;

public enum EvChargerStatus {
    ACTIVE("active"),
    TEMPORARILY_CLOSED("temporarily_closed"),
    PERMANENTLY_CLOSED("permanently_closed"),
    UNKNOWN("unknown");

    private final String value;

    EvChargerStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }
}
