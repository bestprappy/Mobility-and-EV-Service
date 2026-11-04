package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;

import java.util.List;

public interface EvChargerProvider {

    List<EvChargerResponse> nearbyEvChargers(double lat, double lng, int radiusMeters);

    /** One station's current details. Providers that cannot look up by id fail, so callers keep what they saved. */
    default EvChargerResponse evChargerById(String id) {
        throw new UnsupportedOperationException("This provider cannot look up an EV station by id");
    }
}
