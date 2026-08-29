package com.navio.mobilityandevservice.exception;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String message,
        String error,
        Map<String, String> validationErrors
) {
}
