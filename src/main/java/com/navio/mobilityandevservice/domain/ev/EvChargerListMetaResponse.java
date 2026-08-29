package com.navio.mobilityandevservice.domain.ev;

public record EvChargerListMetaResponse(
        String source,
        String tileKey,
        boolean stale,
        boolean refreshed
) {
}
