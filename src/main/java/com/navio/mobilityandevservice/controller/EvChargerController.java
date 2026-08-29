package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.ev.EvChargerListResponse;
import com.navio.mobilityandevservice.domain.ev.EvConnectorType;
import com.navio.mobilityandevservice.service.EvChargerService;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/v1/ev/chargers")
public class EvChargerController {

    private final EvChargerService evChargerService;

    public EvChargerController(EvChargerService evChargerService) {
        this.evChargerService = evChargerService;
    }

    @GetMapping("/near")
    EvChargerListResponse nearby(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") double lat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double lng,
            @RequestParam(defaultValue = "10") @DecimalMin("0.001") @DecimalMax("50.0") double radiusKm,
            @RequestParam(required = false) EvConnectorType connector,
            @RequestParam(required = false) @DecimalMin("0.0") @DecimalMax("1000.0") Double minKw,
            @RequestParam(defaultValue = "false") boolean refresh
    ) {
        return evChargerService.nearby(lat, lng, radiusKm, connector, minKw, refresh);
    }
}
