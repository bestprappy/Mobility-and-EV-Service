package com.navio.mobilityandevservice.provider.usage;

/**
 * Google Maps Platform free-tier buckets Navio counts. Related SKUs share one bucket, so a
 * shared limit keeps every SKU inside it under its own free cap. Default limits sit about 5%
 * under the free cap (Essentials 10,000, Pro 5,000, Enterprise 1,000 a month).
 */
public enum GoogleUsageMeter {
    /** Autocomplete Requests (Essentials). */
    PLACES_AUTOCOMPLETE(9_500),
    /** Place Details Enterprise and Enterprise + Atmosphere: planner cards, Places reader, EV station details. */
    PLACE_DETAILS(950),
    /** Place Details Photos: every photo media request. */
    PLACE_PHOTOS(950),
    /** Text Search Pro, Enterprise and Enterprise + Atmosphere. */
    PLACES_TEXT_SEARCH(950),
    /** Nearby Search Enterprise and Enterprise + Atmosphere, including EV charger searches. */
    PLACES_NEARBY_SEARCH(950),
    /** Compute Routes Essentials and Pro, capped under the smaller Pro allowance. */
    ROUTES(4_750);

    private final int defaultMonthlyLimit;

    GoogleUsageMeter(int defaultMonthlyLimit) {
        this.defaultMonthlyLimit = defaultMonthlyLimit;
    }

    public int defaultMonthlyLimit() {
        return defaultMonthlyLimit;
    }
}
