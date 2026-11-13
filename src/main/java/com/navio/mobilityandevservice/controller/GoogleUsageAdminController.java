package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.usage.GoogleUsageResponse;
import com.navio.mobilityandevservice.service.GoogleUsageReportService;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
