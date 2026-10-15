package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.place.PlaceDiscoveryResponse;
import com.navio.mobilityandevservice.service.PlaceDiscoveryService;
import jakarta.validation.constraints.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequestMapping("/v1/geo/places")
public class PlaceDiscoveryController {
    private final PlaceDiscoveryService service;
    public PlaceDiscoveryController(PlaceDiscoveryService service) { this.service = service; }

    @GetMapping("/discovery")
    public ResponseEntity<PlaceDiscoveryResponse> discover(
            @RequestParam @Size(min=2, max=200) @NotBlank String destination,
            @RequestParam @DecimalMin("-90") @DecimalMax("90") double lat,
            @RequestParam @DecimalMin("-180") @DecimalMax("180") double lng,
            @RequestParam @Min(1) @Max(12) int month,
            @RequestParam(defaultValue="popular") @Pattern(regexp="popular|hidden-gems|worth-a-visit|scenic|food|things-to-do|seasonal") String collection,
            @RequestParam(defaultValue="0") @Min(0) @Max(19) int page,
            @RequestParam(defaultValue="4") @Min(1) @Max(12) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.discover(destination.trim(), lat, lng, month, collection, page, size));
    }

    @GetMapping("/{id}/reader")
    public ResponseEntity<PlaceDiscoveryResponse.Item> reader(@PathVariable @Size(min=1,max=512) String id) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.reader(id));
    }
}
