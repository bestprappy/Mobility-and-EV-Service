package com.navio.mobilityandevservice.service;

import com.navio.mobilityandevservice.domain.place.*;
import com.navio.mobilityandevservice.exception.InvalidRequestException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.provider.google.GooglePlacesAdapter;
import org.springframework.cloud.client.circuitbreaker.CircuitBreaker;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class PlaceDiscoveryService {
    private final GooglePlacesAdapter provider;
    private final CircuitBreaker breaker;
    private final DiscoveryRequestLimiter limiter;
    private static final Set<String> COLLECTIONS = Set.of("popular", "hidden-gems", "worth-a-visit", "scenic", "food", "things-to-do", "seasonal");
    private static final List<String> SIGHTS = List.of("tourist_attraction", "museum", "park");
    private static final String CLIMATE_SOURCE = "https://tmd.go.th/info/%E0%B8%A0%E0%B8%B9%E0%B8%A1%E0%B8%B4%E0%B8%AD%E0%B8%B2%E0%B8%81%E0%B8%B2%E0%B8%A8%E0%B8%82%E0%B8%AD%E0%B8%87%E0%B8%9B%E0%B8%A3%E0%B8%B0%E0%B9%80%E0%B8%97%E0%B8%A8%E0%B9%84%E0%B8%97%E0%B8%A2-%E0%B8%AB%E0%B8%99%E0%B9%89%E0%B8%B2-2";

    public PlaceDiscoveryService(GooglePlacesAdapter provider, CircuitBreakerFactory<?, ?> factory, DiscoveryRequestLimiter limiter) {
        this.provider = provider;
        this.limiter = limiter;
        this.breaker = factory.create("googlePlaces");
    }

    public PlaceDiscoveryResponse discover(String destination, double lat, double lng, int month,
                                           String collection, int page, int size) {
        if (destination == null || destination.isBlank() || destination.length() < 2 || destination.length() > 200
                || !Double.isFinite(lat) || !Double.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180
                || month < 1 || month > 12 || page < 0 || page > 19 || size < 1 || size > 12)
            throw new InvalidRequestException("Invalid discovery destination, coordinates, month or page");
        if (!COLLECTIONS.contains(collection)) throw new InvalidRequestException("Unsupported collection");
        PlaceDiscoveryResponse.Seasonal seasonal = seasonal(destination, lat, lng, month);
        if (collection.equals("seasonal") && seasonal == null)
            return new PlaceDiscoveryResponse(List.of(), false, null);
        try (var permit = limiter.acquire()) {
        return breaker.run(() -> {
            List<PlaceDiscoveryCandidate> ranked = rank(provider.discovery(lat, lng, types(collection, seasonal)), collection);
            int start = Math.min(page * size, ranked.size());
            int end = Math.min(start + size, ranked.size());
            List<PlaceDiscoveryResponse.Item> items = ranked.subList(start, end).stream()
                    .map(provider::withPhoto).map(candidate -> item(candidate, reason(collection, seasonal))).toList();
            return new PlaceDiscoveryResponse(items, end < ranked.size(), seasonal);
        }, failure -> { throw new ProviderUnavailableException("Place discovery is temporarily unavailable", failure); });
        }
    }

    public PlaceDiscoveryResponse.Item reader(String id) {
        try (var permit = limiter.acquire()) {
        return breaker.run(() -> item(provider.reader(id), null), failure -> {
            throw new ProviderUnavailableException("Place details are temporarily unavailable", failure);
        });
        }
    }

    private static PlaceDiscoveryResponse.Item item(PlaceDiscoveryCandidate candidate, String reason) {
        return new PlaceDiscoveryResponse.Item(candidate.place(), reason, candidate.businessStatus(), candidate.photoAttributions(), candidate.photoGoogleMapsUri(), candidate.photos());
    }

    static List<PlaceDiscoveryCandidate> rank(List<PlaceDiscoveryCandidate> input, String collection) {
        Map<String, PlaceDiscoveryCandidate> unique = new LinkedHashMap<>();
        for (PlaceDiscoveryCandidate candidate : input == null ? List.<PlaceDiscoveryCandidate>of() : input) {
            if (candidate == null || candidate.place() == null || candidate.place().providerPlaceId() == null
                    || candidate.place().providerPlaceId().isBlank() || candidate.place().name() == null
                    || candidate.place().name().isBlank() || candidate.place().location() == null) continue;
            if (!"CLOSED_PERMANENTLY".equals(candidate.businessStatus()))
                unique.putIfAbsent(candidate.place().providerPlaceId(), candidate);
        }
        List<PlaceDiscoveryCandidate> pool = new ArrayList<>(unique.values());
        List<PlaceDiscoveryCandidate> rated = pool.stream().filter(c -> c.place().rating() != null
                && c.place().reviewCount() != null && c.place().reviewCount() > 0).toList();
        double mean = rated.stream().mapToDouble(c -> c.place().rating()).average().orElse(0);
        double median = median(rated.stream().mapToLong(c -> c.place().reviewCount()).sorted().toArray());
        if (collection.equals("hidden-gems")) {
            pool.removeIf(c -> c.place().rating() == null || c.place().reviewCount() == null
                    || c.place().rating() < 4.3 || c.place().reviewCount() < 20 || c.place().reviewCount() >= median);
        }
        if (!collection.equals("popular")) {
            pool.sort(Comparator.<PlaceDiscoveryCandidate>comparingDouble(c -> score(c.place(), mean, median))
                    .reversed().thenComparing(c -> c.place().providerPlaceId()));
        }
        return pool;
    }

    private static double median(long[] values) {
        if (values.length == 0) return 0;
        int mid = values.length / 2;
        return values.length % 2 == 0 ? (values[mid - 1] / 2.0 + values[mid] / 2.0) : values[mid];
    }

    private static double score(PlaceDetailResponse place, double mean, double median) {
        if (place.rating() == null || place.reviewCount() == null || place.reviewCount() <= 0) return -1;
        double v = place.reviewCount();
        return (v * place.rating() + median * mean) / (v + median);
    }

    private static List<String> types(String collection, PlaceDiscoveryResponse.Seasonal seasonal) {
        return switch (collection) {
            case "food" -> List.of("restaurant", "cafe", "bakery");
            case "scenic" -> List.of("park", "botanical_garden", "national_park");
            case "things-to-do" -> List.of("museum", "amusement_park", "aquarium", "art_gallery");
            case "seasonal" -> seasonal.label().equals("Indoor discoveries")
                    ? List.of("museum", "art_gallery", "aquarium") : List.of("park", "botanical_garden", "tourist_attraction");
            default -> SIGHTS;
        };
    }

    private static String reason(String collection, PlaceDiscoveryResponse.Seasonal seasonal) {
        return switch (collection) {
            case "popular" -> "Selected from Google's popularity-ranked places nearby.";
            case "hidden-gems" -> "Highly rated, with fewer reviews than this local selection.";
            case "food" -> "A food or cafe stop to consider around your destination.";
            case "scenic" -> "A garden, park or natural setting to explore.";
            case "things-to-do" -> "An attraction or activity to consider for your trip.";
            case "seasonal" -> seasonal.label().equals("Indoor discoveries")
                    ? "An indoor option to consider during the region's typically wetter or hotter months."
                    : "An outdoor option for the region's typically cooler or drier months.";
            default -> "Selected using ratings and review counts from this local selection.";
        };
    }

    // Reviewed 2026-10-15 against TMD climate guidance. Only named city areas are enabled;
    // there is deliberately no country bounding-box or hemisphere climate inference.
    static PlaceDiscoveryResponse.Seasonal seasonal(String destination, double lat, double lng, int month) {
        String name = destination.toLowerCase(Locale.ROOT);
        boolean upper = (name.contains("bangkok") && near(lat, lng, 13.7563, 100.5018))
                || (name.contains("chiang mai") && near(lat, lng, 18.7883, 98.9853));
        boolean west = (name.contains("phuket") && near(lat, lng, 7.8804, 98.3923))
                || (name.contains("krabi") && near(lat, lng, 8.0863, 98.9063));
        boolean east = (name.contains("samui") && near(lat, lng, 9.5120, 100.0136));
        if (!upper && !west && !east) return null;
        boolean indoor = upper ? month >= 3 && month <= 10
                : west ? month >= 5 && month <= 10 : month >= 10 || month <= 1;
        return new PlaceDiscoveryResponse.Seasonal(indoor ? "Indoor discoveries" : "Fresh-air discoveries",
                indoor ? "Indoor options based on this region's typical seasonal climate. This is not a weather forecast."
                        : "Outdoor options based on this region's typical seasonal climate. Check local conditions before visiting.",
                CLIMATE_SOURCE);
    }

    private static boolean near(double lat, double lng, double centerLat, double centerLng) {
        return Math.abs(lat - centerLat) <= 0.45 && Math.abs(lng - centerLng) <= 0.45;
    }
}
