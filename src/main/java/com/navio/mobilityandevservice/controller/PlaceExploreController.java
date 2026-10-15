package com.navio.mobilityandevservice.controller;
import com.navio.mobilityandevservice.domain.place.*;
import com.navio.mobilityandevservice.service.PlaceExploreService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/v1/geo/places/explore")
public class PlaceExploreController {
    private final PlaceExploreService service;
    public PlaceExploreController(PlaceExploreService service) { this.service=service; }
    @GetMapping public ResponseEntity<PlaceExploreResponse> explore(
            @RequestParam(defaultValue="") String country, @RequestParam(defaultValue="") String query,
            @RequestParam(required=false) List<String> interest, @RequestParam(required=false) List<String> type,
            @RequestParam(defaultValue="any") String time, @RequestParam(defaultValue="") String collection,
            @RequestParam(defaultValue="") String destination, @RequestParam(defaultValue="") String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.explore(new PlaceExploreRequest(country,query,interest,type,time,collection,destination),cursor));
    }
    @GetMapping("/suggestions") public ResponseEntity<PlaceExploreService.Suggestions> suggestions(
            @RequestParam String query, @RequestParam(defaultValue="") String country) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.suggestions(query,country));
    }
}
