package com.navio.mobilityandevservice.service;

import java.util.List;

/** Editorial search starting points, not invented place cards or claims of worldwide ranking. */
final class GlobalDiscoveryDestinations {
    record Destination(String city, String country) {}
    private static final List<Destination> DESTINATIONS = List.of(
            new Destination("Tokyo", "JP"), new Destination("Paris", "FR"),
            new Destination("Lisbon", "PT"), new Destination("Chiang Mai", "TH"),
            new Destination("London", "GB"), new Destination("Singapore", "SG"),
            new Destination("Queenstown", "NZ"), new Destination("Vancouver", "CA"),
            new Destination("Osaka", "JP"), new Destination("Bangkok", "TH"),
            new Destination("New York", "US"), new Destination("Barcelona", "ES"),
            new Destination("Cape Town", "ZA"), new Destination("Sydney", "AU"));
    static List<Destination> forCollection(String collection) {
        int offset = switch(collection) {
            case "hidden-gems" -> 2; case "indoor" -> 4; case "scenic" -> 6;
            case "food" -> 8; case "things-to-do" -> 10; case "seasonal" -> 12; default -> 0;
        };
        var result = new java.util.ArrayList<Destination>();
        for (int i=0;i<DESTINATIONS.size();i++) result.add(DESTINATIONS.get((i+offset)%DESTINATIONS.size()));
        return List.copyOf(result);
    }
}
