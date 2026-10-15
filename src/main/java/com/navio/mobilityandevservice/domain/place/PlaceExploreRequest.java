package com.navio.mobilityandevservice.domain.place;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import java.util.*;

public record PlaceExploreRequest(String country, String query, List<String> interests, List<String> types,
                                  String time, String collection, String destination) {
    public static final Set<String> COUNTRIES = Set.of(Locale.getISOCountries());
    public static final Set<String> INTERESTS = Set.of("popular","hidden-gems","indoor","scenic","food","things-to-do");
    public static final Set<String> TYPES = Set.of("restaurant","cafe","bakery","hotel","museum","art_gallery","park","botanical_garden","aquarium","amusement_park","tourist_attraction");
    public PlaceExploreRequest {
        country = country == null ? "" : country.trim().toUpperCase(Locale.ROOT);
        query = query == null ? "" : query.trim();
        interests = interests == null ? List.of() : interests.stream().distinct().sorted().toList();
        types = types == null ? List.of() : types.stream().distinct().sorted().toList();
        time = time == null || time.isBlank() ? "any" : time;
        collection = collection == null ? "" : collection;
        destination = destination == null ? "" : destination.trim();
        if ((!country.isEmpty() && !COUNTRIES.contains(country)) || query.length()>200 || destination.length()>120
                || interests.size()>6 || !INTERESTS.containsAll(interests) || types.size()>12 || !TYPES.containsAll(types)
                || !time.matches("any|month:([1-9]|1[0-2])|season:(spring|summer|autumn|winter|wet|dry)")
                || (!collection.isEmpty() && !INTERESTS.contains(collection) && !collection.equals("seasonal")))
            throw new InvalidRequestException("Invalid country, query, filters or time selection");
    }
    public boolean results() { return !query.isEmpty() || !types.isEmpty(); }
    public String countryName() { return country.isEmpty() ? "" : Locale.of("",country).getDisplayCountry(Locale.ENGLISH); }
}
