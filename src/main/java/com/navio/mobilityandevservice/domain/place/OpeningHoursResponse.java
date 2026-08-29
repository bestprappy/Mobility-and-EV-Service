package com.navio.mobilityandevservice.domain.place;

import java.util.List;

public record OpeningHoursResponse(
        Boolean openNow,
        List<String> weekdayDescriptions,
        String summary
) {

    public OpeningHoursResponse {
        weekdayDescriptions = weekdayDescriptions == null
                ? List.of()
                : List.copyOf(weekdayDescriptions);
    }

    public static OpeningHoursResponse empty() {
        return new OpeningHoursResponse(null, List.of(), null);
    }
}
