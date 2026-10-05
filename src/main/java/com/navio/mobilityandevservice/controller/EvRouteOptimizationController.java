package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationRequest;
import com.navio.mobilityandevservice.domain.optimization.EvRouteOptimizationResponse;
import com.navio.mobilityandevservice.service.optimization.EvRouteOptimizationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/ev-route")
public class EvRouteOptimizationController {

    private final EvRouteOptimizationService optimizationService;

    public EvRouteOptimizationController(EvRouteOptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    // Separate route makes an older mobility deployment fail closed instead of ignoring trip context.
    @PostMapping({"/optimize", "/optimize-canonical"})
    EvRouteOptimizationResponse optimize(@Valid @RequestBody EvRouteOptimizationRequest request) {
        return optimizationService.optimize(request);
    }
}
