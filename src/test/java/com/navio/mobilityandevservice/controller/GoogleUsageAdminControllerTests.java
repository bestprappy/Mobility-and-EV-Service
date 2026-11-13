package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.usage.GoogleUsageResponse;
import com.navio.mobilityandevservice.exception.AdminAccessDeniedException;
import com.navio.mobilityandevservice.exception.GlobalExceptionHandler;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.service.GoogleUsageReportService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class GoogleUsageAdminControllerTests {

    private static final String PATH = "/v1/admin/ev/google-usage";

    private final GoogleUsageReportService service = mock(GoogleUsageReportService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new GoogleUsageAdminController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void passesTheGatewaysRolesToTheServiceAndReturnsAnUncachedReport() throws Exception {
        when(service.currentMonth("ADMIN")).thenReturn(new GoogleUsageResponse(
                Instant.parse("2026-11-01T07:00:00Z"), Instant.parse("2026-12-01T08:00:00Z"),
                Instant.parse("2026-11-09T05:00:00Z"), true, false,
                List.of(new GoogleUsageResponse.Meter("PLACE_PHOTOS", 412, 950, null))));

        mvc.perform(get(PATH).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.limitsEnforced").value(true))
                .andExpect(jsonPath("$.placesDiscoveryEnabled").value(false))
                .andExpect(jsonPath("$.meters[0].meter").value("PLACE_PHOTOS"))
                .andExpect(jsonPath("$.meters[0].used").value(412))
                .andExpect(jsonPath("$.meters[0].limit").value(950));
    }

    @Test
    void aRefusedCallerGets403WithTheSharedErrorBody() throws Exception {
        when(service.currentMonth(any())).thenThrow(new AdminAccessDeniedException());

        mvc.perform(get(PATH).header("X-User-Roles", "USER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Only administrators can view this"));
    }

    @Test
    void aRequestWithNoAssertedRolesStillReachesTheServicesCheck() throws Exception {
        when(service.currentMonth(null)).thenThrow(new AdminAccessDeniedException());

        mvc.perform(get(PATH)).andExpect(status().isForbidden());

        verify(service).currentMonth(null);
    }

    @Test
    void anUnreadableCounterMapsTo503() throws Exception {
        when(service.currentMonth(any())).thenThrow(new ProviderUnavailableException("down", new IllegalStateException()));

        mvc.perform(get(PATH).header("X-User-Roles", "ADMIN"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503));
    }
}
