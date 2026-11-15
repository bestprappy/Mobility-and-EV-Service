package com.navio.mobilityandevservice.controller;

import com.navio.mobilityandevservice.domain.place.PlaceProviderSearchResponse;
import com.navio.mobilityandevservice.exception.AuthenticationRequiredException;
import com.navio.mobilityandevservice.exception.GlobalExceptionHandler;
import com.navio.mobilityandevservice.exception.ProviderSearchDisabledException;
import com.navio.mobilityandevservice.exception.ProviderSearchLimitException;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.service.PlaceProviderSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PlaceProviderSearchControllerTests {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final PlaceProviderSearchService service = mock(PlaceProviderSearchService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new PlaceProviderSearchController(service))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test void returnsATransientGooglePageWithNoStore() throws Exception {
        when(service.search(USER, "museum", "TH", "")).thenReturn(new PlaceProviderSearchResponse(List.of(
                new PlaceProviderSearchResponse.Item("ChIJa", "Museum", "Bangkok", 13.7, 100.5, "Museum", "museum",
                        "TH", "Bangkok", "https://maps.google.com/?cid=1")), "2.tok", 1, 3, "GOOGLE"));
        mvc.perform(get("/v1/geo/place-searches").header("X-User-Id", USER.toString())
                        .param("query", "museum").param("country", "TH"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.source").value("GOOGLE"))
                .andExpect(jsonPath("$.cursor").value("2.tok"))
                .andExpect(jsonPath("$.items[0].providerPlaceId").value("ChIJa"))
                .andExpect(jsonPath("$.items[0].googleMapsUri").value("https://maps.google.com/?cid=1"));
    }

    @Test void callersWithoutAnIdentityGet401WithTheErrorBody() throws Exception {
        when(service.search(isNull(), anyString(), anyString(), anyString()))
                .thenThrow(new AuthenticationRequiredException("Sign in to search beyond Navio's catalogue"));
        mvc.perform(get("/v1/geo/place-searches").param("query", "museum"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Sign in to search beyond Navio's catalogue"));
        verify(service).search(isNull(), anyString(), anyString(), anyString());
    }

    @Test void aSwitchedOffSearchMapsTo503() throws Exception {
        when(service.search(any(), anyString(), anyString(), anyString())).thenThrow(new ProviderSearchDisabledException());
        mvc.perform(get("/v1/geo/place-searches").header("X-User-Id", USER.toString()).param("query", "museum"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.message").value("Search beyond Navio's catalogue is not available"));
    }

    @Test void usedUpAllowancesMapTo429NamingWhichLimit() throws Exception {
        when(service.search(any(), anyString(), anyString(), anyString()))
                .thenThrow(new ProviderSearchLimitException(ProviderSearchLimitException.Scope.MONTH));
        mvc.perform(get("/v1/geo/place-searches").header("X-User-Id", USER.toString()).param("query", "museum"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.error").value("This month's searches beyond the catalogue are used up."));
    }

    @Test void aProviderOutageMapsTo503WithoutLeakingDetail() throws Exception {
        when(service.search(any(), anyString(), anyString(), anyString()))
                .thenThrow(new ProviderUnavailableException("Google search is temporarily unavailable", new IllegalStateException("key=secret")));
        mvc.perform(get("/v1/geo/place-searches").header("X-User-Id", USER.toString()).param("query", "museum"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Try again shortly"));
    }
}
