package com.navio.mobilityandevservice.provider.google;

import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class GooglePlaceLocationMapperTests {
    private GoogleAddressComponent part(String name, String code, String type) {
        return new GoogleAddressComponent(name, code, List.of(type));
    }

    @Test void prefersLocalityToProvince() {
        var value = GooglePlaceLocationMapper.map(List.of(
                part("Chiang Mai", "", "locality"), part("Chiang Mai Province", "", "administrative_area_level_1"),
                part("Thailand", "TH", "country")));
        assertThat(value.city()).isEqualTo("Chiang Mai");
        assertThat(value.region()).isEqualTo("Chiang Mai Province");
        assertThat(value.countryCode()).isEqualTo("TH");
        assertThat(value.countryName()).isEqualTo("Thailand");
    }

    @Test void fallsBackToProvinceWithoutGuessingFromDistrict() {
        var value = GooglePlaceLocationMapper.map(List.of(
                part("Mueang Samut Prakan", "", "administrative_area_level_2"),
                part("Samut Prakan", "", "administrative_area_level_1")));
        assertThat(value.city()).isEqualTo("Samut Prakan");
        assertThat(value.countryCode()).isNull();
        assertThat(value.countryName()).isNull();
    }

    @Test void supportsCityStatesWithoutAProvince() {
        var value = GooglePlaceLocationMapper.map(List.of(
                part("Singapore", "", "locality"), part("Singapore", "sg", "country")));
        assertThat(value.city()).isEqualTo("Singapore");
        assertThat(value.region()).isNull();
        assertThat(value.countryCode()).isEqualTo("SG");
    }

    @Test void preservesCountryOnlyPlaces() {
        var value = GooglePlaceLocationMapper.map(List.of(part("Singapore", "SG", "country")));
        assertThat(value.city()).isNull();
        assertThat(value.countryName()).isEqualTo("Singapore");
    }

    @Test void handlesAbsentAndMalformedComponents() {
        assertThat(GooglePlaceLocationMapper.map(null).countryCode()).isNull();
        var value = GooglePlaceLocationMapper.map(Arrays.asList(null,
                new GoogleAddressComponent("Ignored", "", null),
                part("  ", "", "locality"), part(" New York ", "", "administrative_area_level_1"),
                part("United States", "USA", "country")));
        assertThat(value.city()).isEqualTo("New York");
        assertThat(value.countryCode()).isNull();
    }
}
