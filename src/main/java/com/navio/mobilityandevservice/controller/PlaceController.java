package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.place.PlaceAutocompleteResponse;
import com.navio.mobilityandevservice.domain.place.PlaceDetailResponse;
import com.navio.mobilityandevservice.domain.place.PlaceProviderName;
import com.navio.mobilityandevservice.domain.place.PlaceSearchResponse;
import com.navio.mobilityandevservice.domain.place.PlaceSearchScope;
import com.navio.mobilityandevservice.service.PlaceService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/geo/places")
public class PlaceController {

    private final PlaceService placeService;

    public PlaceController(PlaceService placeService) {
        this.placeService = placeService;
    }

    @GetMapping("/autocomplete")
    PlaceAutocompleteResponse autocomplete(
            @RequestParam @Size(min = 2, max = 200)
            @Pattern(regexp = ".*\\S.*", message = "query must contain non-whitespace characters") String query,
            @RequestParam(required = false) @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @RequestParam(required = false) @DecimalMin("-180.0") @DecimalMax("180.0") Double lng,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String country,
            @RequestParam(required = false) @Size(max = 128) String sessionToken,
            @RequestParam(defaultValue = "ANY") PlaceSearchScope scope
    ) {
        return placeService.autocomplete(query.trim(), lat, lng, country, sessionToken, scope);
    }

    @GetMapping("/{providerPlaceId}")
    PlaceDetailResponse getDetail(
            @PathVariable @Size(min = 1, max = 512) String providerPlaceId,
            @RequestParam(defaultValue = "GOOGLE") PlaceProviderName provider,
            @RequestParam(required = false) @Size(max = 128) String sessionToken
    ) {
        return placeService.getDetail(providerPlaceId, provider, sessionToken);
    }

    @GetMapping("/search")
    PlaceSearchResponse textSearch(
            @RequestParam @Size(min = 2, max = 200)
            @Pattern(regexp = ".*\\S.*", message = "query must contain non-whitespace characters") String query,
            @RequestParam(required = false) @DecimalMin("-90.0") @DecimalMax("90.0") Double lat,
            @RequestParam(required = false) @DecimalMin("-180.0") @DecimalMax("180.0") Double lng
    ) {
        return placeService.textSearch(query.trim(), lat, lng);
    }

    @GetMapping("/nearby")
    PlaceSearchResponse nearby(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam(defaultValue = "800") @Min(1) @Max(50_000) int radius
    ) {
        return placeService.nearby(lat, lng, radius);
    }
}
