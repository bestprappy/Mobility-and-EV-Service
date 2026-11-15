package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.place.PlaceProviderSearchResponse;
import com.navio.mobilityandevservice.service.PlaceProviderSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Explicit "Search beyond Navio's catalogue". Outside /v1/geo/places/** on purpose: the gateway
 * lets guests read /v1/geo/places/{placeId}, and this paid route must stay signed-in only.
 */
@RestController
@RequestMapping("/v1/geo/place-searches")
@RequiredArgsConstructor
public class PlaceProviderSearchController {
    private final PlaceProviderSearchService service;

    @GetMapping
    public ResponseEntity<PlaceProviderSearchResponse> search(
            @RequestHeader(name = "X-User-Id", required = false) UUID userId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam(defaultValue = "") String country,
            @RequestParam(defaultValue = "") String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.search(userId, query, country, cursor));
    }
}
