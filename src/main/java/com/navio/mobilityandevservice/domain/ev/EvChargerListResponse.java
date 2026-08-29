package com.navio.mobilityandevservice.domain.ev;

import java.util.List;

public record EvChargerListResponse(
        List<EvChargerResponse> items,
        EvChargerListMetaResponse meta
) {

    public EvChargerListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
