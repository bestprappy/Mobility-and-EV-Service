package com.navio.mobilityandevservice.controller;
import com.navio.mobilityandevservice.domain.place.PlaceDiscoveryResponse;
import com.navio.mobilityandevservice.exception.GlobalExceptionHandler;
import com.navio.mobilityandevservice.exception.ProviderUnavailableException;
import com.navio.mobilityandevservice.service.PlaceDiscoveryService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class PlaceDiscoveryControllerTests {
    @Test void exhaustedBudgetMapsTo429WithRetryHint() throws Exception {
        var service = mock(PlaceDiscoveryService.class);
        when(service.reader("p")).thenThrow(new com.navio.mobilityandevservice.exception.DiscoveryBusyException());
        var mvc = MockMvcBuilders.standaloneSetup(new PlaceDiscoveryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/v1/geo/places/p/reader")).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After","1"));
    }
    @Test void discoveryReturnsNoStoreHeaderAndTypedEnvelope() throws Exception {
        var service = mock(PlaceDiscoveryService.class);
        when(service.discover(anyString(),anyDouble(),anyDouble(),anyInt(),anyString(),anyInt(),anyInt()))
                .thenReturn(new PlaceDiscoveryResponse(List.of(),false,null));
        var mvc = MockMvcBuilders.standaloneSetup(new PlaceDiscoveryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/v1/geo/places/discovery").param("destination","Tokyo").param("lat","35.6")
                .param("lng","139.6").param("month","1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.items").isArray()).andExpect(jsonPath("$.hasMore").value(false));
        mvc.perform(get("/v1/geo/places/discovery")).andExpect(status().isBadRequest());
    }
    @Test void readerProviderFailureMapsToServiceUnavailable() throws Exception {
        var service = mock(PlaceDiscoveryService.class);
        when(service.reader("p")).thenThrow(new ProviderUnavailableException("offline",new IllegalStateException()));
        var mvc = MockMvcBuilders.standaloneSetup(new PlaceDiscoveryController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        mvc.perform(get("/v1/geo/places/p/reader")).andExpect(status().isServiceUnavailable());
    }
}
