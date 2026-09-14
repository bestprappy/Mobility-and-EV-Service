package com.navio.mobilityandevservice.domain.place;

/** Structured address parts, independent of display text and coordinates. */
public record PlaceLocation(String city, String region, String countryCode, String countryName) {}
