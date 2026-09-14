package com.navio.mobilityandevservice.provider.google;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.navio.mobilityandevservice.domain.ev.EvChargerResponse;
import com.navio.mobilityandevservice.domain.ev.EvChargerStatus;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.domain.place.OpeningHoursResponse;
import com.navio.mobilityandevservice.domain.place.PlaceAutocompleteResponse;
import com.navio.mobilityandevservice.domain.place.PlaceDetailResponse;
import com.navio.mobilityandevservice.domain.place.PlaceLocationResponse;
import com.navio.mobilityandevservice.domain.place.PlaceProviderName;
import com.navio.mobilityandevservice.domain.place.PlaceSearchResponse;
import com.navio.mobilityandevservice.domain.place.PlaceSearchScope;
import com.navio.mobilityandevservice.domain.place.PlaceSuggestionResponse;
import com.navio.mobilityandevservice.exception.ProviderClientException;
import com.navio.mobilityandevservice.provider.EvChargerProvider;
import com.navio.mobilityandevservice.provider.PlaceProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

@Slf4j
@Component
public class GooglePlacesAdapter implements PlaceProvider, EvChargerProvider {

    private static final int MAX_AUTOCOMPLETE_RESULTS = 8;
    private static final int MAX_SEARCH_RESULTS = 20;
    private static final String AUTOCOMPLETE_FIELD_MASK = String.join(",",
            "suggestions.placePrediction.placeId",
            "suggestions.placePrediction.text.text",
            "suggestions.placePrediction.structuredFormat.mainText.text",
            "suggestions.placePrediction.structuredFormat.secondaryText.text",
            "suggestions.placePrediction.types"
    );
    private static final String PLACE_FIELD_MASK = String.join(",",
            "id",
            "displayName",
            "formattedAddress",
            "addressComponents",
            "location",
            "internationalPhoneNumber",
            "nationalPhoneNumber",
            "websiteUri",
            "photos",
            "regularOpeningHours",
            "rating",
            "userRatingCount",
            "primaryTypeDisplayName"
    );
    private static final String SEARCH_FIELD_MASK = String.join(",",
            "places.id",
            "places.displayName",
            "places.formattedAddress",
            "places.location",
            "places.rating",
            "places.userRatingCount",
            "places.primaryTypeDisplayName"
    );
    private static final String EV_CHARGER_FIELD_MASK = String.join(",",
            "places.id",
            "places.displayName",
            "places.formattedAddress",
            "places.location",
            "places.regularOpeningHours",
            "places.businessStatus",
            "places.rating",
            "places.userRatingCount",
            "places.evChargeOptions"
    );
    private static final List<String> EV_CHARGER_TYPES = List.of("electric_vehicle_charging_station");
    private static final List<String> DESTINATION_TYPES = List.of("(regions)");

    private final RestClient restClient;

    public GooglePlacesAdapter(@Qualifier("googlePlacesRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public PlaceAutocompleteResponse autocomplete(
            String query,
            Double lat,
            Double lng,
            String country,
            String sessionToken,
            PlaceSearchScope scope
    ) {
        GoogleAutocompleteRequest request = new GoogleAutocompleteRequest(
                query,
                locationBias(lat, lng, 50_000),
                blankToNull(sessionToken),
                scope == PlaceSearchScope.DESTINATION ? DESTINATION_TYPES : null,
                country == null ? null : List.of(country.toLowerCase(Locale.ROOT))
        );

        GoogleAutocompleteResponse response = execute("autocomplete", () -> restClient.post()
                .uri("/places:autocomplete")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-FieldMask", AUTOCOMPLETE_FIELD_MASK)
                .body(request)
                .retrieve()
                .body(GoogleAutocompleteResponse.class));

        List<PlaceSuggestionResponse> suggestions = safeList(response.suggestions()).stream()
                .map(GoogleSuggestion::placePrediction)
                .filter(Objects::nonNull)
                .map(prediction -> mapSuggestion(prediction, sessionToken))
                .limit(MAX_AUTOCOMPLETE_RESULTS)
                .toList();

        return new PlaceAutocompleteResponse(suggestions);
    }

    @Override
    public PlaceDetailResponse getDetail(String providerPlaceId, String sessionToken) {
        GooglePlace place = execute("place detail", () -> restClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/places/{providerPlaceId}");
                    if (sessionToken != null && !sessionToken.isBlank()) {
                        builder.queryParam("sessionToken", sessionToken);
                    }
                    return builder.build(providerPlaceId);
                })
                .header("X-Goog-FieldMask", PLACE_FIELD_MASK)
                .retrieve()
                .body(GooglePlace.class));

        return mapPlace(place, providerPlaceId, loadPhotoUrl(place));
    }

    @Override
    public PlaceSearchResponse textSearch(String query, Double lat, Double lng) {
        GoogleTextSearchRequest request = new GoogleTextSearchRequest(
                query,
                locationBias(lat, lng, 50_000),
                MAX_SEARCH_RESULTS
        );

        GooglePlaceListResponse response = execute("text search", () -> restClient.post()
                .uri("/places:searchText")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-FieldMask", SEARCH_FIELD_MASK)
                .body(request)
                .retrieve()
                .body(GooglePlaceListResponse.class));

        return mapSearchResponse(response);
    }

    @Override
    public PlaceSearchResponse nearby(double lat, double lng, int radiusMeters) {
        GoogleNearbySearchRequest request = new GoogleNearbySearchRequest(
                new GoogleLocationRestriction(new GoogleCircle(
                        new GoogleLatLng(lat, lng),
                        radiusMeters
                )),
                MAX_SEARCH_RESULTS,
                "DISTANCE",
                null
        );

        GooglePlaceListResponse response = execute("nearby search", () -> restClient.post()
                .uri("/places:searchNearby")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-FieldMask", SEARCH_FIELD_MASK)
                .body(request)
                .retrieve()
                .body(GooglePlaceListResponse.class));

        return mapSearchResponse(response);
    }

    @Override
    public List<EvChargerResponse> nearbyEvChargers(double lat, double lng, int radiusMeters) {
        GoogleNearbySearchRequest request = new GoogleNearbySearchRequest(
                new GoogleLocationRestriction(new GoogleCircle(
                        new GoogleLatLng(lat, lng),
                        radiusMeters
                )),
                MAX_SEARCH_RESULTS,
                "DISTANCE",
                EV_CHARGER_TYPES
        );

        GooglePlaceListResponse response = execute("EV station nearby search", () -> restClient.post()
                .uri("/places:searchNearby")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Goog-FieldMask", EV_CHARGER_FIELD_MASK)
                .body(request)
                .retrieve()
                .body(GooglePlaceListResponse.class));

        return safeList(response.places()).stream()
                .filter(Objects::nonNull)
                .map(this::mapEvCharger)
                .toList();
    }

    private PlaceSearchResponse mapSearchResponse(GooglePlaceListResponse response) {
        List<PlaceDetailResponse> items = safeList(response.places()).stream()
                .filter(Objects::nonNull)
                .map(place -> mapPlace(place, place.id(), null))
                .toList();
        return new PlaceSearchResponse(items);
    }

    private PlaceSuggestionResponse mapSuggestion(
            GooglePlacePrediction prediction,
            String sessionToken
    ) {
        String mainText = text(prediction.structuredFormat() == null
                ? null
                : prediction.structuredFormat().mainText());
        if (mainText == null) {
            mainText = text(prediction.text());
        }

        String secondaryText = text(prediction.structuredFormat() == null
                ? null
                : prediction.structuredFormat().secondaryText());

        return new PlaceSuggestionResponse(
                PlaceProviderName.GOOGLE,
                prediction.placeId(),
                valueOrEmpty(mainText),
                valueOrEmpty(secondaryText),
                safeList(prediction.types()),
                blankToNull(sessionToken)
        );
    }

    private PlaceDetailResponse mapPlace(
            GooglePlace place,
            String requestedPlaceId,
            String photoUrl
    ) {
        String placeId = firstNonBlank(place.id(), requestedPlaceId);
        String name = text(place.displayName());
        String category = text(place.primaryTypeDisplayName());
        GoogleLatLng location = place.location();

        if (location == null) {
            throw new ProviderClientException("Google Places returned a place without coordinates");
        }

        return new PlaceDetailResponse(
                PlaceProviderName.GOOGLE,
                placeId,
                valueOrEmpty(name),
                place.formattedAddress(),
                new PlaceLocationResponse(
                        location.latitude(),
                        location.longitude(),
                        place.formattedAddress(),
                        placeId
                ),
                firstNonBlank(place.internationalPhoneNumber(), place.nationalPhoneNumber()),
                place.websiteUri(),
                photoUrl,
                mapOpeningHours(place.regularOpeningHours()),
                category,
                category,
                place.rating(),
                place.userRatingCount(),
                GooglePlaceLocationMapper.map(place.addressComponents())
        );
    }

    private OpeningHoursResponse mapOpeningHours(GoogleOpeningHours openingHours) {
        if (openingHours == null) {
            return OpeningHoursResponse.empty();
        }
        List<String> descriptions = safeList(openingHours.weekdayDescriptions());
        String summary = descriptions.isEmpty() ? null : String.join(" | ", descriptions);
        return new OpeningHoursResponse(openingHours.openNow(), descriptions, summary);
    }

    private EvChargerResponse mapEvCharger(GooglePlace place) {
        GoogleLatLng location = place.location();
        if (location == null) {
            throw new ProviderClientException("Google Places returned an EV station without coordinates");
        }

        List<GoogleConnectorAggregation> aggregations = place.evChargeOptions() == null
                ? List.of()
                : safeList(place.evChargeOptions().connectorAggregation());
        List<EvConnectorType> connectorTypes = aggregations.stream()
                .map(GoogleConnectorAggregation::type)
                .map(this::mapConnectorType)
                .distinct()
                .toList();
        double maxKw = aggregations.stream()
                .map(GoogleConnectorAggregation::maxChargeRateKw)
                .filter(Objects::nonNull)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0);
        int totalConnectors = connectorCount(place.evChargeOptions(), aggregations);

        return new EvChargerResponse(
                valueOrEmpty(place.id()),
                valueOrEmpty(text(place.displayName())),
                null,
                new PlaceLocationResponse(
                        location.latitude(),
                        location.longitude(),
                        place.formattedAddress(),
                        place.id()
                ),
                place.formattedAddress(),
                null,
                connectorTypes,
                maxKw,
                totalConnectors,
                availableConnectorCount(aggregations),
                null,
                mapOpeningHours(place.regularOpeningHours()),
                "GOOGLE_PLACES",
                "GOOGLE_CACHED",
                mapBusinessStatus(place.businessStatus()),
                place.rating() == null ? 0 : place.rating(),
                place.userRatingCount() == null ? 0 : place.userRatingCount(),
                place.evChargeOptions() == null ? 0.65 : 0.82,
                false
        );
    }

    private int connectorCount(
            GoogleEvChargeOptions chargeOptions,
            List<GoogleConnectorAggregation> aggregations
    ) {
        if (chargeOptions != null && chargeOptions.connectorCount() != null) {
            return chargeOptions.connectorCount();
        }
        return aggregations.stream()
                .map(GoogleConnectorAggregation::count)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();
    }

    private Integer availableConnectorCount(List<GoogleConnectorAggregation> aggregations) {
        if (aggregations.isEmpty()
                || aggregations.stream().anyMatch(aggregation -> aggregation.availableCount() == null)) {
            return null;
        }
        return aggregations.stream()
                .mapToInt(GoogleConnectorAggregation::availableCount)
                .sum();
    }

    private EvConnectorType mapConnectorType(String googleType) {
        if (googleType == null) {
            return EvConnectorType.OTHER;
        }
        return switch (googleType) {
            case "EV_CONNECTOR_TYPE_J1772" -> EvConnectorType.J1772;
            case "EV_CONNECTOR_TYPE_TYPE_2" -> EvConnectorType.TYPE2;
            case "EV_CONNECTOR_TYPE_CHADEMO" -> EvConnectorType.CHADEMO;
            case "EV_CONNECTOR_TYPE_CCS_COMBO_1" -> EvConnectorType.CCS1;
            case "EV_CONNECTOR_TYPE_CCS_COMBO_2" -> EvConnectorType.CCS2;
            case "EV_CONNECTOR_TYPE_NACS", "EV_CONNECTOR_TYPE_TESLA" -> EvConnectorType.NACS;
            case "EV_CONNECTOR_TYPE_UNSPECIFIED_GB_T" -> EvConnectorType.GB_T;
            default -> EvConnectorType.OTHER;
        };
    }

    private EvChargerStatus mapBusinessStatus(String businessStatus) {
        if (businessStatus == null) {
            return EvChargerStatus.UNKNOWN;
        }
        return switch (businessStatus) {
            case "OPERATIONAL" -> EvChargerStatus.ACTIVE;
            case "CLOSED_TEMPORARILY" -> EvChargerStatus.TEMPORARILY_CLOSED;
            case "CLOSED_PERMANENTLY" -> EvChargerStatus.PERMANENTLY_CLOSED;
            default -> EvChargerStatus.UNKNOWN;
        };
    }

    private String loadPhotoUrl(GooglePlace place) {
        GooglePhoto photo = safeList(place.photos()).stream().findFirst().orElse(null);
        if (photo == null || photo.name() == null || photo.name().isBlank()) {
            return null;
        }

        try {
            String[] resourceSegments = photo.name().split("/");
            GooglePhotoMedia media = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .pathSegment(resourceSegments)
                            .pathSegment("media")
                            .queryParam("maxWidthPx", 960)
                            .queryParam("skipHttpRedirect", true)
                            .build())
                    .retrieve()
                    .body(GooglePhotoMedia.class);
            return media == null ? null : media.photoUri();
        } catch (RestClientException exception) {
            log.warn("Google place photo could not be loaded for placeId={}", place.id());
            return null;
        }
    }

    private <T> T execute(String operation, Supplier<T> request) {
        try {
            T response = request.get();
            if (response == null) {
                throw new ProviderClientException("Google Places returned an empty " + operation + " response");
            }
            return response;
        } catch (ProviderClientException exception) {
            throw exception;
        } catch (RestClientException exception) {
            throw new ProviderClientException("Google Places " + operation + " failed", exception);
        }
    }

    private static GoogleLocationBias locationBias(Double lat, Double lng, double radiusMeters) {
        if (lat == null || lng == null) {
            return null;
        }
        return new GoogleLocationBias(new GoogleCircle(new GoogleLatLng(lat, lng), radiusMeters));
    }

    private static String text(GoogleText value) {
        return value == null ? null : blankToNull(value.text());
    }

    private static String firstNonBlank(String first, String second) {
        String normalizedFirst = blankToNull(first);
        return normalizedFirst == null ? blankToNull(second) : normalizedFirst;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GoogleAutocompleteRequest(
            String input,
            GoogleLocationBias locationBias,
            String sessionToken,
            List<String> includedPrimaryTypes,
            List<String> includedRegionCodes
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GoogleTextSearchRequest(
            String textQuery,
            GoogleLocationBias locationBias,
            Integer pageSize
    ) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record GoogleNearbySearchRequest(
            GoogleLocationRestriction locationRestriction,
            Integer maxResultCount,
            String rankPreference,
            List<String> includedTypes
    ) {
    }

    private record GoogleLocationBias(GoogleCircle circle) {
    }

    private record GoogleLocationRestriction(GoogleCircle circle) {
    }

    private record GoogleCircle(GoogleLatLng center, double radius) {
    }

    private record GoogleLatLng(double latitude, double longitude) {
    }

    private record GoogleAutocompleteResponse(List<GoogleSuggestion> suggestions) {
    }

    private record GoogleSuggestion(GooglePlacePrediction placePrediction) {
    }

    private record GooglePlacePrediction(
            String placeId,
            GoogleText text,
            GoogleStructuredFormat structuredFormat,
            List<String> types
    ) {
    }

    private record GoogleStructuredFormat(GoogleText mainText, GoogleText secondaryText) {
    }

    private record GoogleText(String text) {
    }

    private record GooglePlaceListResponse(List<GooglePlace> places) {
    }

    private record GooglePlace(
            String id,
            GoogleText displayName,
            String formattedAddress,
            GoogleLatLng location,
            String internationalPhoneNumber,
            String nationalPhoneNumber,
            String websiteUri,
            List<GooglePhoto> photos,
            GoogleOpeningHours regularOpeningHours,
            Double rating,
            Long userRatingCount,
            GoogleText primaryTypeDisplayName,
            String businessStatus,
            GoogleEvChargeOptions evChargeOptions,
            List<GoogleAddressComponent> addressComponents
    ) {
    }

    private record GoogleEvChargeOptions(
            Integer connectorCount,
            List<GoogleConnectorAggregation> connectorAggregation
    ) {
    }

    private record GoogleConnectorAggregation(
            String type,
            Double maxChargeRateKw,
            Integer count,
            Integer availableCount,
            Integer outOfServiceCount
    ) {
    }

    private record GooglePhoto(String name) {
    }

    private record GooglePhotoMedia(String photoUri) {
    }

    private record GoogleOpeningHours(Boolean openNow, List<String> weekdayDescriptions) {
    }
}
