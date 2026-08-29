package com.navio.mobilityandevservice.provider;

import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;

import java.util.List;

public interface EvChargerProvider {

    List<EvChargerResponse> nearbyEvChargers(double lat, double lng, int radiusMeters);
}
