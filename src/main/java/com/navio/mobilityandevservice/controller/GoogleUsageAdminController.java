package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.usage.GoogleUsageResponse;
import com.navio.mobilityandevservice.domain.usage.UpdateLimitOverrideRequest;
import com.navio.mobilityandevservice.service.GoogleUsageReportService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/ev/google-usage")
public class GoogleUsageAdminController {

    private final GoogleUsageReportService service;

    public GoogleUsageAdminController(GoogleUsageReportService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<GoogleUsageResponse> currentMonth(
            @RequestHeader(name = "X-User-Roles", required = false) String roles
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.currentMonth(roles));
    }

    @PutMapping("/limit-override")
    public ResponseEntity<GoogleUsageResponse> updateLimitOverride(
            @RequestHeader(name = "X-User-Roles", required = false) String roles,
            @RequestHeader(name = "X-User-Id", required = false) UUID userId,
            @Valid @RequestBody UpdateLimitOverrideRequest request
    ) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(service.updateLimitOverride(roles, userId, request.ignoreLimits()));
    }
}
