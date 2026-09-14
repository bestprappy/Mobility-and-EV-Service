package com.navio.mobilityandevservice.provider.google;

import com.navio.mobilityandevservice.domain.place.PlaceLocation;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class GooglePlaceLocationMapper {
    private GooglePlaceLocationMapper() {}

    public static PlaceLocation map(List<GoogleAddressComponent> components) {
        var parts = components == null ? List.<GoogleAddressComponent>of() : components;
        String region = longText(find(parts, "administrative_area_level_1"));
        String city = longText(find(parts, "locality"));
        var country = find(parts, "country");
        String code = country == null ? null : clean(country.shortText());
        code = code != null && code.matches("[A-Za-z]{2}") ? code.toUpperCase(Locale.ROOT) : null;
        return new PlaceLocation(city == null ? region : city, region, code, longText(country));
    }

    private static GoogleAddressComponent find(List<GoogleAddressComponent> parts, String type) {
        return parts.stream().filter(Objects::nonNull)
                .filter(part -> part.types() != null && part.types().contains(type))
                .findFirst().orElse(null);
    }

    private static String longText(GoogleAddressComponent part) {
        return part == null ? null : clean(part.longText());
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
