package com.navio.mobilityandevservice.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTests {
    @Test
    void anInvalidTransitDepartureUsesTheMappedClientErrorResponse() {
        var response = new GlobalExceptionHandler().handleInvalidRequest(
                new InvalidRequestException("Transit departure must be within the past 7 days or next 100 days"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().error()).contains("Transit departure");
        assertThat(response.getBody().status()).isEqualTo(400);
    }
}
